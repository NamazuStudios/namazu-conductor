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
    val status : JobStatus,

    /**
     * The network endpoints exposed by the running job, populated once the job reaches
     * [JobStatus.RUNNING]. Empty while the job is [JobStatus.PENDING].
     */
    val endpoints : List<JobEndpoint> = emptyList(),

    /**
     * Provider-specific detail object. The shape is determined by the [dev.getelements.conductor.service.OrchestrationService]
     * implementation and is opaque to this module. Serialised as-is by the REST layer.
     */
    val details : Any? = null,

    /**
     * The containers running as part of this execution, mirroring
     * [dev.getelements.conductor.service.JobProfile.containers]. Used to select a specific
     * container when calling [dev.getelements.conductor.service.OrchestrationService.streamStdio].
     */
    val containers : List<ContainerRef> = emptyList(),

    /**
     * The effective namespace the running workload was created in, populated by providers that
     * have a namespace concept (Kubernetes). Providers without one (ECS, EdgeGap) leave this null.
     * Distinct from any [JobScope] supplied at launch time — this reflects where the workload
     * actually landed. Exposed to the terminal attach policy layer.
     */
    val namespace : String? = null

)
