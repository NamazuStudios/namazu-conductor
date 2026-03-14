package dev.getelements.conductor.service

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

}
