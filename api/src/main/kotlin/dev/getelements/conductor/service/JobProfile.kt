package dev.getelements.conductor.service

import dev.getelements.conductor.ContainerRef

/**
 * Describes a pre-configured job template available on an [OrchestrationService]. Each [OrchestrationService]
 * exposes its own set of profiles via [OrchestrationService.getAvailableProfiles]; the contents of a
 * profile (container image, resource limits, etc.) are managed by the orchestrator implementation
 * and are opaque to callers. A profile is referenced by [dev.getelements.conductor.JobRequest]
 * to select which template the submitted job should use.
 */
interface JobProfile {

    /**
     * The unique identifier of this [JobProfile] within its [OrchestrationService].
     */
    val id: String;

    /**
     * The containers a job launched from this profile will have. Providers that only ever run a
     * single container per job return a single-element list with that container marked
     * [ContainerRef.primary]. Providers that support multiple containers per workload (e.g.
     * Kubernetes pods with sidecars) return one entry per container.
     */
    val containers: List<ContainerRef>

    /**
     * Whether this profile is meant to be launched with an interactive terminal attached. Providers
     * that support it should use this as a hint to default [dev.getelements.conductor.JobRequest.tty]
     * (and a sensible shell command) when a caller doesn't specify one explicitly. Defaults to `false`
     * for providers that don't have a way to express this.
     */
    val terminalJob: Boolean get() = false

    /**
     * An optional human-readable description of this profile, in Markdown. `null` if the provider
     * doesn't support or wasn't given one.
     */
    val description: String? get() = null

    /**
     * The namespace this profile's jobs are created in, for providers that have a namespace
     * concept (Kubernetes). `null` for providers without one — mirrors
     * [dev.getelements.conductor.JobExecution.namespace]. Surfaced so authorization layers (e.g.
     * [dev.getelements.conductor.JobAccessPolicy]) can decide on an execute *before* dispatch,
     * from the profile alone.
     */
    val namespace: String? get() = null

}
