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
     * Targets the profile's primary (first) container only.
     */
    val environment : Map<String, String> = emptyMap(),

    /**
     * Environment variables injected into a *specific* container at runtime, keyed by container
     * name — as reported by [dev.getelements.conductor.service.Daemon.containers] (a
     * [dev.getelements.conductor.ContainerRef.id]). Additive, mirroring
     * [dev.getelements.conductor.JobRequest.containerEnvironment]: [environment] keeps applying to
     * the primary container, an empty map changes nothing, and an unknown container name fails the
     * deploy outright with [dev.getelements.conductor.exception.UnknownContainerException] before
     * anything is created. Precedence on the same container: profile-declared env, then
     * [environment] (primary only), then the matching entry here.
     */
    val containerEnvironment : Map<String, Map<String, String>> = emptyMap(),

    /**
     * Secrets the daemon consumes by reference, injected at deploy time under their
     * [dev.getelements.conductor.SecretRef.envKey] — the orchestrator resolves each
     * [dev.getelements.conductor.service.StoredSecret] so the value never appears in the
     * workload's spec, logs, or annotations (see [dev.getelements.conductor.service.SecretStore]).
     *
     * Empty by default. A provider with no
     * [dev.getelements.conductor.service.SecretStore] rejects any deploy carrying entries here
     * outright; a provider with one rejects unknown names, unknown container targets, and env-key
     * collisions before anything is created — same discipline as
     * [dev.getelements.conductor.exception.UnknownContainerException].
     */
    val secrets : List<SecretRef> = emptyList(),

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
    val scope : List<JobScope> = emptyList(),

    /**
     * Caller-supplied metadata overrides, merged over the
     * [dev.getelements.conductor.service.Daemon.metadata] the daemon was declared with. Mirrors
     * [dev.getelements.conductor.JobRequest.metadata], including the reserved-prefix restriction.
     *
     * @throws dev.getelements.conductor.exception.ReservedMetadataKeyException if any key carries
     * the reserved `namazu.conductor` prefix, which Conductor reserves for its own semantics.
     */
    val metadata : Map<String, String> = emptyMap()

)
