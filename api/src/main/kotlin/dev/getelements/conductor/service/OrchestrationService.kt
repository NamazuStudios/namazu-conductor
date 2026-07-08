package dev.getelements.conductor.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.JobStdio
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future

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

    /**
     * Returns a [Future] for the supplied job status. This allows calling code to wait for a status before proceeding
     * with calling code.
     */
    fun getFutureForStatus(execution: JobExecution, status: JobStatus) : Future<JobExecution>;

    /**
     * Returns a [CompletionStage] for the supplied job status, resolving asynchronously as the
     * underlying provider observes the transition (e.g. via a push notification/watch) rather than
     * requiring a dedicated blocked thread. The default implementation delegates to
     * [getFutureForStatus] on a background thread; providers with a native async/event-driven
     * status mechanism should override this directly.
     */
    fun getStageForStatus(execution: JobExecution, status: JobStatus): CompletionStage<JobExecution> =
        CompletableFuture.supplyAsync { getFutureForStatus(execution, status).get() }

    /**
     * Lists all executions currently visible to this provider (pending, running, and recently
     * completed workloads, depending on what the underlying platform surfaces). The returned
     * list is a point-in-time snapshot.
     *
     * @return a list of [JobExecution]s known to this provider
     */
    fun listExecutions(): List<JobExecution>

    /**
     * Stops the running job identified by [execution].
     */
    fun stop(execution: JobExecution)

    /**
     * Opens a live, bidirectional stdio session for [execution]: [JobStdio.stdin] accepts writes
     * forwarded to the process, [JobStdio.stdout]/[JobStdio.stderr] are separate live read streams.
     * The session ends when the process exits or the caller closes the returned [JobStdio].
     *
     * The default implementation throws [UnsupportedOperationException]; providers with no native or
     * bridged stdio access leave it unimplemented. Providers that support this in principle but can't
     * currently reach the process (job not started, process no longer running, bridge not present,
     * etc.) throw [dev.getelements.conductor.exception.StdioUnavailableException] instead.
     */
    fun streamStdio(execution: JobExecution): JobStdio =
        throw UnsupportedOperationException("${this::class.simpleName} does not support stdio streaming")

}
