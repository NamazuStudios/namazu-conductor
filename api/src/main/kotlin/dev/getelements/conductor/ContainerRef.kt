package dev.getelements.conductor

/**
 * A stable reference to a single container within a job's workload, as reported by
 * [dev.getelements.conductor.service.JobProfile.containers] (what's attachable before the job runs)
 * and [JobExecution.containers] (what's actually running). [id] is the identifier passed back to
 * [dev.getelements.conductor.service.OrchestrationService.streamStdio] to select this container;
 * for providers that support only one container per job, exactly one [ContainerRef] is returned
 * with [primary] set to `true`.
 */
data class ContainerRef(

    /**
     * The stable identifier for this container, used to address it via
     * [dev.getelements.conductor.service.OrchestrationService.streamStdio].
     */
    val id: String,

    /**
     * A human-readable name for this container, shown in UIs. Providers that don't have a richer
     * naming concept may set this equal to [id].
     */
    val name: String,

    /**
     * `true` for the container targeted by default when no explicit container id is supplied
     * (e.g. to [dev.getelements.conductor.service.OrchestrationService.streamStdio]). Exactly one
     * entry in a given container list should be primary.
     */
    val primary: Boolean

)
