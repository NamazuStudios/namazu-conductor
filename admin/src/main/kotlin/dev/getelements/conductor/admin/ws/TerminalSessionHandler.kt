package dev.getelements.conductor.admin.ws

import com.fasterxml.jackson.databind.ObjectMapper
import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.TerminalAttachContext
import dev.getelements.conductor.TerminalAttachVote
import dev.getelements.conductor.TerminalLivenessEvents
import dev.getelements.conductor.TerminalLivenessPongEvent
import dev.getelements.conductor.admin.ConductorAdminApplication
import dev.getelements.conductor.admin.ElementLookup
import dev.getelements.conductor.admin.model.TerminalInitMessage
import dev.getelements.elements.sdk.ElementRegistrySupplier
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.auth.SessionService
import jakarta.websocket.CloseReason
import jakarta.websocket.MessageHandler
import jakarta.websocket.PongMessage
import jakarta.websocket.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shared logic behind [PrimaryContainerTerminalEndpoint] and [ContainerTerminalEndpoint]: resolves
 * the job across all deployed Elements (the URL carries no element name), opens a [JobStdio] session
 * via [dev.getelements.conductor.service.OrchestrationService.streamStdio], and bridges its blocking
 * streams to the WebSocket session.
 *
 * Wire format: the connection is authorized on-the-fly rather than via a pre-minted credential —
 * browsers can't set an `Authorization` header on the native `WebSocket()` constructor, so the very
 * first text frame the client sends must be a [TerminalInitMessage] JSON payload carrying the caller's
 * session secret (and optionally a command override); [onOpen] suspends (via a coroutine, not the
 * calling thread — required since JSR-356 callbacks must not block) waiting for it, with a timeout.
 * Only once that's validated does stdio actually open. Every text frame after that first one is
 * instead a small hand-rolled resize control message (`{"type":"resize","cols":N,"rows":N}`) sent
 * client-to-server only — a regex match is used instead of a JSON library since this is a single,
 * tightly-scoped message shape we define ourselves, not general-purpose JSON parsing. Binary frames
 * carry raw terminal bytes in both directions (keystrokes in, pty output out) throughout.
 *
 * Who may attach is delegated to any deployed
 * [dev.getelements.conductor.TerminalAttachPolicy] elements (see [authorized]); when none are
 * deployed, the historical rule applies: only `SUPERUSER` sessions. Authentication itself (the
 * session secret's validity) is always enforced here first, never delegated.
 *
 * Bell-triggered toast notifications (issue #37) are deliberately *not* a control message here: a
 * container surfaces one by writing a custom OSC escape sequence to its own stdout, which already
 * flows through unmodified as part of the ordinary binary pty stream and is parsed client-side by
 * xterm.js — see `admin/README.md`'s "Toast notifications" section and `admin/ui/superuser/terminal.ts`.
 */
internal object TerminalSessionHandler {

    private val logger = LoggerFactory.getLogger(TerminalSessionHandler::class.java)

    private const val STDIO_PROPERTY = "conductor.jobStdio"
    private const val WRITE_LOCK_PROPERTY = "conductor.writeLock"
    private const val PING_FUTURE_PROPERTY = "conductor.pingFuture"
    private const val INIT_DEFERRED_PROPERTY = "conductor.initDeferred"
    private const val AUTH_JOB_PROPERTY = "conductor.authJob"
    private const val LOOKUP_PROPERTY = "conductor.lookup"

    // Comfortably under common container/proxy idle-timeout defaults (typically 30-60s), so an
    // otherwise-silent terminal (no keystrokes, no pty output) never gets closed out from under it.
    private const val PING_INTERVAL_SECONDS = 25L

    // Generous enough for a client to construct and send its first frame right after the socket opens,
    // short enough that a connection nobody ever authenticates doesn't linger.
    private const val AUTH_TIMEOUT_SECONDS = 10L

    private val EMPTY_PING_PAYLOAD: ByteBuffer = ByteBuffer.allocate(0)

    // Shared across all terminal sessions - pings are infrequent and cheap, one daemon thread suffices.
    private val pingScheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "conductor-terminal-ping").apply { isDaemon = true }
    }

    /**
     * Minimum interval between [TerminalLivenessEvents.PONG] publishes per job, in milliseconds —
     * read (lazily, on first pong) from this Element's
     * [ConductorAdminApplication.TERMINAL_LIVENESS_INTERVAL_SECONDS] attribute, defaulting to the
     * attribute's declared default when absent or unparsable. The throttle is per **job**, not per
     * session: N concurrently attached terminals still yield one event per interval.
     */
    private val livenessIntervalMs: Long by lazy {
        val registry = ElementRegistrySupplier.getElementLocal(TerminalSessionHandler::class.java).get()
        val ownElement = registry.stream().toList()
            .firstOrNull { it.elementRecord.isPartOfElement(TerminalSessionHandler::class.java) }
        val seconds = ownElement?.elementRecord?.attributes()
            ?.getAttributeOptional(ConductorAdminApplication.TERMINAL_LIVENESS_INTERVAL_SECONDS)
            ?.map { it.toString().toLongOrNull() }
            ?.orElse(null)
            ?: ConductorAdminApplication.DEFAULT_TERMINAL_LIVENESS_INTERVAL_SECONDS
        if (seconds <= 0) {
            logger.warn(
                "Attribute '{}' is '{}' — not a positive second count; using the default of {}s",
                ConductorAdminApplication.TERMINAL_LIVENESS_INTERVAL_SECONDS, seconds,
                ConductorAdminApplication.DEFAULT_TERMINAL_LIVENESS_INTERVAL_SECONDS
            )
            ConductorAdminApplication.DEFAULT_TERMINAL_LIVENESS_INTERVAL_SECONDS
        } else {
            seconds
        } * 1000
    }

    /** Last [TerminalLivenessEvents.PONG] publish (epoch millis) per job id — the dedup window. */
    private val lastLivenessByJob = ConcurrentHashMap<String, Long>()

    // Publishes are quick (the fan-out is synchronous; consumers are contractually non-blocking),
    // so a single daemon thread serializes them safely off the WS/ping threads.
    private val livenessPublisher = Executors.newSingleThreadExecutor {
        Thread(it, "conductor-terminal-liveness").apply { isDaemon = true }
    }

    // Shared across all terminal sessions' auth-wait coroutines - SupervisorJob so one session's
    // failure/cancellation can't affect another's; Dispatchers.Default since this is short-lived,
    // non-blocking coordination work (the actual stdio pump remains a dedicated Thread, unchanged).
    private val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val objectMapper = ObjectMapper()

    private val RESIZE_REGEX = Regex(
        """"type"\s*:\s*"resize".*?"cols"\s*:\s*(\d+).*?"rows"\s*:\s*(\d+)"""
    )

    fun onOpen(session: Session, jobId: String, containerId: String?) {
        val initDeferred = CompletableDeferred<String>()
        session.userProperties[INIT_DEFERRED_PROPERTY] = initDeferred

        // Protocol pongs are deliverable only to a programmatically-registered handler (JSR-356
        // annotations cannot receive them). Browsers answer the server's pings automatically at
        // the protocol level, so every healthy attached client produces a pong cadence for free —
        // the raw material for the terminal-liveness event (issue #81). Pongs arriving before the
        // session is authorized are ignored by onPong.
        session.addMessageHandler(PongMessage::class.java, MessageHandler.Whole<PongMessage> { onPong(session) })

        val authJob = handlerScope.launch {
            val rawInit = withTimeoutOrNull(AUTH_TIMEOUT_SECONDS * 1000) { initDeferred.await() }
            if (rawInit == null) {
                closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "timed out waiting for auth message")
                return@launch
            }

            val init = try {
                objectMapper.readValue(rawInit, TerminalInitMessage::class.java)
            } catch (e: Exception) {
                closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "malformed init message")
                return@launch
            }

            val authSession = try {
                ElementLookup.findCoreService(TerminalSessionHandler::class.java, SessionService::class.java)
                    .checkAndRefreshSessionIfNecessary(init.sessionSecret)
            } catch (e: Exception) {
                closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "invalid or expired session")
                return@launch
            }

            val lookup = ElementLookup.findByJobId(TerminalSessionHandler::class.java, jobId)
            if (lookup == null) {
                closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "job not found: $jobId")
                return@launch
            }

            if (!authorized(session, authSession, lookup, containerId, init.command)) return@launch

            openStdio(session, lookup, containerId, init.command)
        }
        session.userProperties[AUTH_JOB_PROPERTY] = authJob
    }

    /**
     * Consults every deployed [TerminalAttachPolicy] Element for a vote on whether [authSession]
     * may attach a terminal to [lookup]'s job, enforcing the aggregation rules:
     *
     *  - any `DENY` vetoes outright (regardless of who's asking — a superuser is subject to the
     *    same vote as anyone else);
     *  - otherwise a single `ALLOW` is enough to proceed;
     *  - `PASS` votes abstain and are ignored.
     *
     * When no policy Element is deployed (or every vote abstains) the historical gate applies:
     * only `SUPERUSER` sessions may attach. Closes the socket (leaving the caller to bail out) on
     * denial; returns `true` when the attach may proceed.
     */
    private fun authorized(
        session: Session,
        authSession: dev.getelements.elements.sdk.model.session.Session,
        lookup: ElementLookup.JobLookup,
        containerId: String?,
        command: List<String>?
    ): Boolean {
        var denyReason: String? = null
        var anyAllow = false
        ElementLookup.forEachTerminalAttachPolicy(TerminalSessionHandler::class.java) { _, policy ->
            val vote = try {
                policy.authorize(
                    TerminalAttachContext(
                        session = authSession,
                        user = authSession.user,
                        jobId = lookup.execution.id,
                        containerId = containerId,
                        command = command,
                        elementName = lookup.elementName,
                        execution = lookup.execution,
                        namespace = lookup.execution.namespace
                    )
                )
            } catch (e: Exception) {
                logger.warn("TerminalAttachPolicy [{}] threw; treating as PASS", policy::class.java.name, e)
                TerminalAttachVote.PASS
            }
            when (vote) {
                TerminalAttachVote.DENY -> if (denyReason == null) {
                    denyReason = "denied by attach policy (${policy::class.java.simpleName})"
                }
                TerminalAttachVote.ALLOW -> anyAllow = true
                TerminalAttachVote.PASS -> {}
            }
        }

        if (denyReason != null) {
            closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, denyReason)
            return false
        }
        if (anyAllow) return true

        if (authSession.user?.level != User.Level.SUPERUSER) {
            closeQuietly(session, CloseReason.CloseCodes.VIOLATED_POLICY, "insufficient privilege level")
            return false
        }
        return true
    }

    private fun openStdio(session: Session, lookup: ElementLookup.JobLookup, containerId: String?, command: List<String>?) {
        val stdio = try {
            lookup.service.streamStdio(lookup.execution, containerId, command)
        } catch (e: Exception) {
            logger.warn("Failed to open stdio for job '{}' container '{}'", lookup.execution.id, containerId, e)
            closeQuietly(session, CloseReason.CloseCodes.UNEXPECTED_CONDITION, (e.message ?: "stdio unavailable").take(120))
            return
        }

        session.userProperties[STDIO_PROPERTY] = stdio
        // Stored before the ping schedule starts: the pong handler keys off it, and the first
        // scheduled ping's pong must find the lookup already in place.
        session.userProperties[LOOKUP_PROPERTY] = lookup

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

        val reader = Thread({ pumpStdout(session, stdio, writeLock) }, "conductor-terminal-${lookup.execution.id}")
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

    /**
     * A pong arrived from the attached client (browsers answer the heartbeat's pings
     * protocol-automatically). Ignored until the session is authorized; otherwise claims the
     * job's [livenessIntervalMs] dedup window and — on success — publishes
     * [TerminalLivenessEvents.PONG] fire-and-forget. Never touches the session state: a pong is
     * a liveness signal, not a message to reply to.
     */
    private fun onPong(session: Session) {
        val lookup = session.userProperties[LOOKUP_PROPERTY] as? ElementLookup.JobLookup ?: return
        val jobId = lookup.execution.id
        val now = System.currentTimeMillis()
        var claimed = false
        lastLivenessByJob.compute(jobId) { _, last ->
            if (last != null && now - last < livenessIntervalMs) last else { claimed = true; now }
        }
        if (claimed) livenessPublisher.submit { publishLiveness(lookup) }
    }

    /**
     * Publishes [TerminalLivenessEvents.PONG] for [lookup]'s job. Runs on [livenessPublisher] —
     * never the WS/ping threads — because the platform's event fan-out is synchronous and
     * same-thread (LinkedPublisher), so a consumer's slow-but-contractually-disallowed blocking
     * must not reach the terminal session either way.
     */
    private fun publishLiveness(lookup: ElementLookup.JobLookup) {
        try {
            val registry = ElementRegistrySupplier.getElementLocal(TerminalSessionHandler::class.java).get()
            val ownElement = registry.stream().toList()
                .firstOrNull { it.elementRecord.isPartOfElement(TerminalSessionHandler::class.java) }
            if (ownElement == null) {
                logger.warn(
                    "Could not resolve this Element in the registry; not publishing terminal liveness for job '{}'",
                    lookup.execution.id
                )
                return
            }
            ownElement.publish(
                TerminalLivenessPongEvent(
                    jobId = lookup.execution.id,
                    elementName = lookup.elementName,
                    execution = lookup.execution,
                    namespace = lookup.execution.namespace
                )
            )
            logger.debug("Published terminal liveness event for job '{}'", lookup.execution.id)
        } catch (e: Exception) {
            logger.warn("Failed to publish terminal liveness event for job '{}'", lookup.execution.id, e)
        }
    }

    fun onBinaryMessage(session: Session, data: ByteArray) {
        val stdio = stdioOf(session) ?: return
        runCatching {
            stdio.stdin.write(data)
            stdio.stdin.flush()
        }
    }

    fun onTextMessage(session: Session, text: String) {
        @Suppress("UNCHECKED_CAST")
        val initDeferred = session.userProperties[INIT_DEFERRED_PROPERTY] as? CompletableDeferred<String>
        if (initDeferred != null && !initDeferred.isCompleted) {
            initDeferred.complete(text)
            return
        }

        val stdio = stdioOf(session) ?: return
        val match = RESIZE_REGEX.find(text) ?: return
        val cols = match.groupValues[1].toIntOrNull() ?: return
        val rows = match.groupValues[2].toIntOrNull() ?: return
        stdio.resize?.invoke(cols, rows)
    }

    fun onClose(session: Session) {
        cancelPing(session)
        (session.userProperties[AUTH_JOB_PROPERTY] as? Job)?.cancel()
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
