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
    val namespace : String? = null,

    /**
     * The metadata actually present on the running workload — the profile's declared set with
     * [dev.getelements.conductor.JobRequest.metadata] applied over it, read back from the live
     * resource rather than echoed from the request.
     *
     * This can be a **superset** of the declared set: on Kubernetes the created workload also
     * inherits annotations from the inner `PodTemplate` template block, which Conductor does not
     * control. Reporting what landed, rather than forcing equality with what was requested, is the
     * useful answer for a presentation layer.
     *
     * Empty for providers with no metadata channel.
     */
    val metadata : Map<String, String> = emptyMap()

)
