package dev.getelements.conductor.service

import dev.getelements.conductor.ContainerRef

/**
 * Describes a pre-configured daemon template available on a [DaemonOrchestrationService]. Each
 * [DaemonOrchestrationService] exposes its own set of daemons via
 * [DaemonOrchestrationService.getAvailableDaemons]; the contents of a daemon (container image,
 * resource limits, etc.) are managed by the orchestrator implementation and are opaque to callers.
 * A daemon is referenced by [dev.getelements.conductor.DaemonRequest] to select which template the
 * submitted deployment should use.
 */
interface Daemon {

    /**
     * The unique identifier of this [Daemon] within its [DaemonOrchestrationService].
     */
    val id: String;

    /**
     * The containers a deployment of this [Daemon] will have. Mirrors
     * [JobProfile.containers]: providers that only ever run a single container per workload return
     * a single-element list with that container marked [ContainerRef.primary]; providers that
     * support multiple containers (e.g. Kubernetes pods with sidecars) return one entry per
     * container. Defaults to empty for providers without the concept — the container names
     * [dev.getelements.conductor.DaemonRequest.containerEnvironment] keys against come from here.
     */
    val containers: List<ContainerRef> get() = emptyList()

    /**
     * An optional friendly display name for this daemon, shown by generic consumers (the admin
     * dashboard) in place of the raw [id]. Mirrors
     * [dev.getelements.conductor.service.JobProfile.name]; `null` if the provider has no name
     * source or none was given — callers should fall back to [id].
     */
    val name: String? get() = null

    /**
     * An optional human-readable description of this daemon, in Markdown. Mirrors
     * [dev.getelements.conductor.service.JobProfile.description]; `null` if the provider doesn't
     * support or wasn't given one.
     */
    val description: String? get() = null

    /**
     * Free-form, provider-agnostic presentation metadata declared alongside this daemon by the
     * underlying infrastructure. Mirrors
     * [dev.getelements.conductor.service.JobProfile.metadata] exactly, including the full-key,
     * no-filtering reporting rule — see [dev.getelements.conductor.Metadata] for the reserved-prefix
     * rule and the run-time override path.
     *
     * Defaults to empty for providers with no metadata channel.
     */
    val metadata: Map<String, String> get() = emptyMap()

}
