package dev.getelements.conductor.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.elements.sdk.annotation.ElementServiceExport

/**
 * Orchestrates container executions in the cloud. Implementations of this interface are responsible
 * for dispatching [dev.getelements.conductor.JobRequest]s to an underlying container platform and
 * returning a [dev.getelements.conductor.JobExecution] that represents the running workload.
 */
@ElementServiceExport
interface OrchestrationService {

    /**
     * Queries the subsystem to get the available [JobProfile]s that can be used to submit a job. This returns an
     * opaque interface which may only be consumed by the same object which returned it.
     *
     * @return a list of [JobProfile]s supported by this orchestrator implementation
     */
    fun getAvailableProfiles(): List<JobProfile>;

    /**
     * Finds the available profile with the supplied id returning null if it does not exist.
     */
    fun findAvailableProfile(id : String): JobProfile? {
        return getAvailableProfiles().find { it.id == id }
    }

    /**
     * Executes the job with the supplied. [JobRequest].
     */
    fun execute(request : JobRequest): JobExecution;

}
