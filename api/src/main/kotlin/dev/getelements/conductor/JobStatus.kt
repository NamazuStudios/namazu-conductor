package dev.getelements.conductor

/**
 * Indicates the lifecycle status of a [JobExecution]. Transitions generally flow
 * [PENDING] → [RUNNING] → [COMPLETED] or [FAILED].
 */
enum class JobStatus {

    /**
     * The job has been submitted but has not yet started running.
     */
    PENDING,

    /**
     * The job is actively running on the orchestration platform.
     */
    RUNNING,

    /**
     * The job has finished successfully.
     */
    COMPLETED,

    /**
     * The job has failed. Inspect the [JobExecution] for details.
     */
    FAILED

}