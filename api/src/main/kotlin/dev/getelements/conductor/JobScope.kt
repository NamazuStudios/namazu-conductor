package dev.getelements.conductor

/**
 * Indicates the kind of scoping boundary applied to a job within the target orchestration
 * environment. Different orchestration backends support different scope types — unsupported
 * types are silently ignored by the [dev.getelements.conductor.service.OrchestrationService]
 * implementation.
 */
enum class ScopeType {

    /**
     * Scopes the job to a named namespace. Supported by Kubernetes, where it overrides the
     * provider's configured default namespace for the created workload.
     */
    NAMESPACE,

    /**
     * Scopes the job to a named cluster. Supported by AWS ECS, where it overrides the provider's
     * configured default cluster for the launched task.
     */
    CLUSTER,
}

/**
 * Marker interface for job scoping hints supplied in a [JobRequest]. Each implementation
 * corresponds to a [ScopeType] and carries the data required by the orchestration backend to
 * honour that scope. Backends without an equivalent concept (e.g. EdgeGap, where scoping is
 * already fully determined by the app/version identified by the [JobProfile][dev.getelements.conductor.service.JobProfile])
 * silently ignore [JobScope] entirely.
 */
interface JobScope {
    val type: ScopeType
}

/**
 * Scopes the job to the given Kubernetes [namespace], overriding the provider's configured
 * default namespace for the created workload (and its `Service`, if any). The `PodTemplate`
 * backing the profile is still resolved from its own namespace — this only affects where the
 * running workload is created.
 */
data class NamespaceScope(val namespace: String) : JobScope {

    /**
     * Always returns [ScopeType.NAMESPACE].
     */
    override val type: ScopeType
        get() = ScopeType.NAMESPACE

}

/**
 * Scopes the job to the given AWS ECS [cluster], overriding the provider's configured default
 * cluster for the launched task.
 */
data class ClusterScope(val cluster: String) : JobScope {

    /**
     * Always returns [ScopeType.CLUSTER].
     */
    override val type: ScopeType
        get() = ScopeType.CLUSTER

}