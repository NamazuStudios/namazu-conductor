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

}
