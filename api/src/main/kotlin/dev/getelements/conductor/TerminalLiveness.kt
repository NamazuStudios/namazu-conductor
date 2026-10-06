package dev.getelements.conductor

import dev.getelements.elements.sdk.Event

/**
 * Event names published by the admin element's terminal WebSocket layer.
 */
object TerminalLivenessEvents {

    /**
     * Published when a protocol-level pong from an attached terminal client proves a live,
     * authenticated session is watching the referenced job. Dispatch is throttled per job
     * (see `dev.getelements.conductor.admin.terminal-liveness.interval.seconds`), so a job with
     * several concurrently attached terminals yields one event per interval, not one per session.
     *
     * Consumers **must not block**: the admin element publishes synchronously on its own dispatch
     * thread, but the same-thread fan-out means a blocking consumer stalls every other consumer of
     * this event and the publisher's executor behind it. Long-running work (e.g. a remote exec into
     * the job's container) belongs on the consumer's own executor. Consumer failures are logged by
     * the platform and never affect the terminal session or other consumers.
     */
    const val PONG = "dev.getelements.conductor.terminal.liveness.pong"

}

/**
 * A pong-driven terminal-liveness event for one job: a browser or other client attached to the
 * job's terminal answered the admin element's protocol ping, so the job has a live, authenticated
 * observer.
 *
 * This is deliberately unopinionated about what liveness *means* — the admin element only reports
 * the signal. The motivating consumer is an agent-side idle watchdog: an element consuming this
 * event remotely execs the agent container's checkin script, so the pod's lifetime is governed by
 * websocket liveness rather than process heuristics (issue #81, companion to namazu-agent#97).
 */
data class TerminalLivenessPongEvent(

    /**
     * The id of the job whose terminal session received the pong.
     */
    val jobId: String,

    /**
     * The name of the provider Element that owns [execution].
     */
    val elementName: String,

    /**
     * The execution, as resolved by the provider's
     * [dev.getelements.conductor.service.OrchestrationService.listExecutions] — enough to act on
     * the job (e.g. open a [dev.getelements.conductor.JobStdio] via the owning provider's
     * [dev.getelements.conductor.service.OrchestrationService.streamStdio]).
     */
    val execution: JobExecution,

    /**
     * The effective namespace the job's workload was created in, or `null` for providers without
     * a namespace concept (ECS, EdgeGap).
     */
    val namespace: String?,

    ) : Event {

    override fun getEventName(): String = TerminalLivenessEvents.PONG

    override fun getEventArguments(): List<Any> = listOfNotNull(jobId, elementName, execution, namespace)

}
