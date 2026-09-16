package dev.getelements.conductor.admin.ws

import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.admin.ElementLookup
import jakarta.websocket.CloseReason
import jakarta.websocket.Session
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

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

    private val RESIZE_REGEX = Regex(
        """"type"\s*:\s*"resize".*?"cols"\s*:\s*(\d+).*?"rows"\s*:\s*(\d+)"""
    )

    fun onOpen(session: Session, jobId: String, containerId: String?) {
        val ticket = session.requestParameterMap["ticket"]?.firstOrNull()
        val consumed = ticket?.let { TerminalTicketStore.consume(it, jobId, containerId) }
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

        val reader = Thread({ pumpStdout(session, stdio) }, "conductor-terminal-$jobId")
        reader.isDaemon = true
        reader.start()
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
        stdioOf(session)?.let { stdio -> runCatching { stdio.close() } }
    }

    fun onError(session: Session, throwable: Throwable) {
        logger.warn("Terminal session error", throwable)
        onClose(session)
    }

    private fun pumpStdout(session: Session, stdio: JobStdio) {
        val buffer = ByteArray(8192)
        try {
            while (session.isOpen) {
                val read = stdio.stdout.read(buffer)
                if (read < 0) break
                session.basicRemote.sendBinary(ByteBuffer.wrap(buffer, 0, read))
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
