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
     * Targets the profile's primary (first) container only.
     */
    val environment : Map<String, String> = emptyMap(),

    /**
     * Environment variables injected into a *specific* container at runtime, keyed by container
     * name — as reported by [dev.getelements.conductor.service.JobProfile.containers] (a
     * [dev.getelements.conductor.ContainerRef.id]). This is additive: [environment] keeps applying
     * to the primary container exactly as before, and leaving this map empty changes nothing.
     *
     * When both [environment] and an entry here target the same container, precedence is: the
     * container's own profile-declared env, overridden by [environment] (primary only), overridden
     * by the matching entry here — so a per-container key wins over a flat key on the same
     * container.
     *
     * Naming a container that doesn't exist in the profile fails the launch outright — never a
     * silent drop — with
     * [dev.getelements.conductor.exception.UnknownContainerException] before anything is created.
     * Providers with no multi-container concept (e.g. EdgeGap, whose environment is
     * deployment-wide) reject every named entry the same way.
     */
    val containerEnvironment : Map<String, Map<String, String>> = emptyMap(),

    /**
     * Optional [JobPlacement] hints that influence where the job is scheduled. Ignored if the
     * underlying [dev.getelements.conductor.service.OrchestrationService] implementation does not support placement.
     */
    val placement : List<JobPlacement> = emptyList(),

    /**
     * Optional [JobScope] hints that override the default scoping boundary (e.g. Kubernetes
     * namespace, ECS cluster) used by the underlying orchestration backend. Ignored if the
     * underlying [dev.getelements.conductor.service.OrchestrationService] implementation does not
     * support scoping.
     */
    val scope : List<JobScope> = emptyList(),

    /**
     * Requests that the job be launched with an interactive pty attached (tty allocated, stdin kept
     * open) rather than as a plain batch process, so it can be attached to as a terminal via
     * [dev.getelements.conductor.service.OrchestrationService.streamStdio]. Providers that don't
     * support interactive terminal jobs throw [UnsupportedOperationException] from
     * [dev.getelements.conductor.service.OrchestrationService.execute] when this is `true`.
     */
    val tty : Boolean = false,

    /**
     * Caller-supplied metadata overrides, merged over the
     * [dev.getelements.conductor.service.JobProfile.metadata] the profile was declared with. Any
     * key present here wins; keys not mentioned are left as the profile declared them. A key the
     * profile doesn't declare is added.
     *
     * Ignored if the underlying [dev.getelements.conductor.service.OrchestrationService]
     * implementation has no metadata channel.
     *
     * @throws dev.getelements.conductor.exception.ReservedMetadataKeyException if any key carries
     * the reserved `namazu.conductor` prefix, which Conductor reserves for its own semantics.
     */
    val metadata : Map<String, String> = emptyMap()

)
