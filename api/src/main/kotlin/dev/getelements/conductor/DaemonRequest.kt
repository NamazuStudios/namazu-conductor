package dev.getelements.conductor

import dev.getelements.conductor.service.Daemon

/**
 * Represents a request to deploy a daemon. Contains the [Daemon] that describes the workload,
 * the command and arguments to run inside the container, environment variable overrides, and
 * optional [JobPlacement] hints for the orchestration layer.
 */
data class DaemonRequest (

    /**
     * The [Daemon] that describes the container image and resource configuration to use.
     */
    val profile : Daemon,

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
     * Optional [JobPlacement] hints that influence where the daemon is scheduled. Ignored if the
     * underlying [dev.getelements.conductor.service.DaemonOrchestrationService] implementation does
     * not support placement.
     */
    val placement : List<JobPlacement> = emptyList(),

    /**
     * Optional [JobScope] hints that override the default scoping boundary (e.g. Kubernetes
     * namespace, ECS cluster) used by the underlying orchestration backend. Ignored if the
     * underlying [dev.getelements.conductor.service.DaemonOrchestrationService] implementation does
     * not support scoping.
     */
    val scope : List<JobScope> = emptyList()

)
