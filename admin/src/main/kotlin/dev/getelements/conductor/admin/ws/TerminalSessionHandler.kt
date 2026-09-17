package dev.getelements.conductor.admin.ws

import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.admin.ElementLookup
import dev.getelements.elements.sdk.ElementSupplier
import jakarta.websocket.CloseReason
import jakarta.websocket.Session
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shared logic behind [PrimaryContainerTerminalEndpoint] and [ContainerTerminalEndpoint]: resolves
 * the job across all deployed Elements (the URL carries no element name), opens a [JobStdio] session
 * via [dev.getelements.conductor.service.OrchestrationService.streamStdio], and bridges its blocking
 * streams to the WebSocket session.
 *
 * Wire format: binary WebSocket frames carry raw terminal bytes in both directions (keystrokes in,
 * pty output out); text frames carry a small hand-rolled resize control message
 * (`{"type":"resize","cols":N,"rows":N}`) sent client-to-server only — a regex match is used instead
 * of a JSON library since this is a single, tightly-scoped message shape we define ourselves, not
 * general-purpose JSON parsing.
 */
internal object TerminalSessionHandler {

    private val logger = LoggerFactory.getLogger(TerminalSessionHandler::class.java)

    private const val STDIO_PROPERTY = "conductor.jobStdio"
    private const val WRITE_LOCK_PROPERTY = "conductor.writeLock"
    private const val PING_FUTURE_PROPERTY = "conductor.pingFuture"

    // Comfortably under common container/proxy idle-timeout defaults (typically 30-60s), so an
    // otherwise-silent terminal (no keystrokes, no pty output) never gets closed out from under it.
    private const val PING_INTERVAL_SECONDS = 25L

    private val EMPTY_PING_PAYLOAD: ByteBuffer = ByteBuffer.allocate(0)

    // Shared across all terminal sessions - pings are infrequent and cheap, one daemon thread suffices.
    private val pingScheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "conductor-terminal-ping").apply { isDaemon = true }
    }

    private val RESIZE_REGEX = Regex(
        """"type"\s*:\s*"resize".*?"cols"\s*:\s*(\d+).*?"rows"\s*:\s*(\d+)"""
    )

    // TerminalSessionHandler is instantiated by the JSR-356 container (per the @ServerEndpoint
    // classes that delegate to it), not by Guice, so it can't take TerminalTicketStore as a
    // constructor dependency the way ConductorAdminJobsResource does. ElementSupplier/ServiceLocator
    // is the SDK's sanctioned way to reach a Guice-managed singleton from non-Guice-managed code.
    private val ticketStore: TerminalTicketStore
        get() = ElementSupplier.getElementLocal(TerminalSessionHandler::class.java).get().serviceLocator
            .getInstance(TerminalTicketStore::class.java)

    fun onOpen(session: Session, jobId: String, containerId: String?) {
        val ticket = session.requestParameterMap["ticket"]?.firstOrNull()
        val consumed = ticket?.let { ticketStore.consume(it, jobId, containerId) }
        if (consumed == null) {
            closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "invalid or expired ticket")
            return
        }

        val lookup = ElementLookup.findByJobId(TerminalSessionHandler::class.java, jobId)
        if (lookup == null) {
            closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "job not found: $jobId")
            return
        }

        val stdio = try {
            lookup.service.streamStdio(lookup.execution, containerId, consumed.command)
        } catch (e: Exception) {
            logger.warn("Failed to open stdio for job '{}' container '{}'", jobId, containerId, e)
            closeQuietly(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, (e.message ?: "stdio unavailable").take(120))
            return
        }

        session.userProperties[STDIO_PROPERTY] = stdio

        // Guards every session.basicRemote send (both the stdout pump below and the ping heartbeat)
        // so they're never in flight concurrently - see TerminalSessionHandler's plan notes: the spec
        // documents a possible IllegalStateException from concurrent Basic-remote sends, independent
        // of whatever the underlying container happens to serialize internally.
        val writeLock = Any()
        session.userProperties[WRITE_LOCK_PROPERTY] = writeLock

        val pingFuture = pingScheduler.scheduleAtFixedRate(
            { sendPing(session, writeLock) },
            PING_INTERVAL_SECONDS,
            PING_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
        session.userProperties[PING_FUTURE_PROPERTY] = pingFuture

        val reader = Thread({ pumpStdout(session, stdio, writeLock) }, "conductor-terminal-$jobId")
        reader.isDaemon = true
        reader.start()
    }

    private fun sendPing(session: Session, writeLock: Any) {
        if (!session.isOpen) {
            cancelPing(session)
            return
        }
        val sent = runCatching {
            synchronized(writeLock) { session.basicRemote.sendPing(EMPTY_PING_PAYLOAD) }
        }
        if (sent.isFailure) cancelPing(session)
    }

    private fun cancelPing(session: Session) {
        (session.userProperties[PING_FUTURE_PROPERTY] as? ScheduledFuture<*>)?.cancel(false)
    }

    fun onBinaryMessage(session: Session, data: ByteArray) {
        val stdio = stdioOf(session) ?: return
        runCatching {
            stdio.stdin.write(data)
            stdio.stdin.flush()
        }
    }

    fun onTextMessage(session: Session, text: String) {
        val stdio = stdioOf(session) ?: return
        val match = RESIZE_REGEX.find(text) ?: return
        val cols = match.groupValues[1].toIntOrNull() ?: return
        val rows = match.groupValues[2].toIntOrNull() ?: return
        stdio.resize?.invoke(cols, rows)
    }

    fun onClose(session: Session) {
        cancelPing(session)
        stdioOf(session)?.let { stdio -> runCatching { stdio.close() } }
    }

    fun onError(session: Session, throwable: Throwable) {
        logger.warn("Terminal session error", throwable)
        onClose(session)
    }

    private fun pumpStdout(session: Session, stdio: JobStdio, writeLock: Any) {
        val buffer = ByteArray(8192)
        try {
            while (session.isOpen) {
                val read = stdio.stdout.read(buffer)
                if (read < 0) break
                synchronized(writeLock) { session.basicRemote.sendBinary(ByteBuffer.wrap(buffer, 0, read)) }
            }
        } catch (e: Exception) {
            logger.debug("Terminal stdout pump ending", e)
        } finally {
            runCatching { session.close() }
            runCatching { stdio.close() }
        }
    }

    private fun stdioOf(session: Session): JobStdio? = session.userProperties[STDIO_PROPERTY] as? JobStdio

    private fun closeQuietly(session: Session, code: CloseReason.CloseCode, reason: String) {
        runCatching { session.close(CloseReason(code, reason)) }
    }

}
