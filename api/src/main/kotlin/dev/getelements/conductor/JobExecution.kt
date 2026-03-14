package dev.getelements.conductor

/**
 * Represents an execution of a job submitted via [dev.getelements.conductor.service.OrchestrationService].
 * An instance is returned when a [JobRequest] is dispatched and tracks the running workload
 * on the underlying container platform.
 */
data class JobExecution(

    /**
     * The ID of the running job.
     */
    val id : String,

    /**
     * The status of the running job.
     */
    var status : JobStatus

)
