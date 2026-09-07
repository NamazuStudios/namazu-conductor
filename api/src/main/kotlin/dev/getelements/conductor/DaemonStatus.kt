package dev.getelements.conductor

/**
 * Indicates the lifecycle status of a [DaemonExecution]. Unlike [JobStatus], there is no terminal
 * "completed" state — a daemon is a persistent workload that the underlying platform keeps running
 * indefinitely. Transitions generally flow [PENDING] → [RUNNING], oscillating into [DEGRADED] as
 * replica health fluctuates over the daemon's lifetime, with [FAILED] reserved for cases the
 * platform cannot recover from on its own.
 */
enum class DaemonStatus {

    /**
     * The daemon has been deployed but no replicas are ready yet.
     */
    PENDING,

    /**
     * At least the desired number of replicas are ready and serving.
     */
    RUNNING,

    /**
     * Some, but not all, of the desired replicas are ready. The daemon is still considered live.
     */
    DEGRADED,

    /**
     * The daemon's underlying resource is missing or has entered an unrecoverable state.
     */
    FAILED

}
