package dev.getelements.conductor

/**
 * Represents a deployment of a daemon submitted via [dev.getelements.conductor.service.DaemonOrchestrationService].
 * An instance is returned when a [DaemonRequest] is deployed and tracks the persistent workload
 * on the underlying container platform.
 */
data class DaemonExecution(

    /**
     * The ID of the deployed daemon.
     */
    val id : String,

    /**
     * The status of the deployed daemon.
     */
    val status : DaemonStatus,

    /**
     * The number of replicas the underlying platform is configured to keep running.
     */
    val desiredCount : Int,

    /**
     * The number of replicas currently ready and serving.
     */
    val runningCount : Int = 0,

    /**
     * The lower autoscaling bound, if autoscaling has been configured for this daemon.
     */
    val minCount : Int? = null,

    /**
     * The upper autoscaling bound, if autoscaling has been configured for this daemon.
     */
    val maxCount : Int? = null,

    /**
     * The network endpoints exposed by the running daemon. Empty if the underlying platform has
     * not exposed a stable endpoint for this daemon.
     */
    val endpoints : List<JobEndpoint> = emptyList(),

    /**
     * Provider-specific detail object. The shape is determined by the
     * [dev.getelements.conductor.service.DaemonOrchestrationService] implementation and is opaque
     * to this module. Serialised as-is by the REST layer.
     */
    val details : Any? = null

)
