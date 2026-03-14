package dev.getelements.conductor

import dev.getelements.conductor.service.JobProfile

/**
 * Represents a request to execute a job. Contains the [JobProfile] that describes the workload,
 * the command and arguments to run inside the container, environment variable overrides, and
 * optional [JobPlacement] hints for the orchestration layer.
 */
data class JobRequest (

    /**
     * The [JobProfile] that describes the container image and resource configuration to use.
     */
    val profile : JobProfile,

    /**
     * Arguments to pass to the container's entrypoint. Appended after [command] when both are set.
     */
    val args : List<String> = emptyList(),

    /**
     * Overrides the default command (entrypoint) of the container image.
     */
    val command : List<String> = emptyList(),

    /**
     * Environment variables to inject into the container at runtime, as a map of name to value.
     */
    val environment : Map<String, String> = emptyMap(),

    /**
     * Optional [JobPlacement] hints that influence where the job is scheduled. Ignored if the
     * underlying [dev.getelements.conductor.service.OrchestrationService] implementation does not support placement.
     */
    val placement : List<JobPlacement> = emptyList()

)
