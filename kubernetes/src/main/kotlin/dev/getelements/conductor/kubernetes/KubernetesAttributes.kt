package dev.getelements.conductor.kubernetes

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Attribute name constants for the Kubernetes Element. Each constant is the fully-qualified attribute
 * key used by the Elements SDK to bind configuration values via [@Named][com.google.inject.name.Named]
 * injection. Default values are declared on each constant via [@ElementDefaultAttribute].
 */
object KubernetesAttributes {

    /**
     * The namespace in which `PodTemplate`s are discovered and workloads are created.
     * Defaults to `"default"`.
     */
    @ElementDefaultAttribute("default")
    const val NAMESPACE = "dev.getelements.conductor.kubernetes.namespace"

    /**
     * Controls how `PodTemplate` and workload **discovery** scopes namespaces:
     * `"configured"` (the default) lists only [NAMESPACE], while `"any"` lists across
     * every namespace in the cluster — the mode a multi-tenant deployment uses when
     * per-tenant workloads live in per-tenant namespaces (e.g. Namazu Cloud instances,
     * whose namespace is named after the instance id). Only listing
     * (`OrchestrationService.getAvailableProfiles` / `OrchestrationService.listExecutions`)
     * is affected: [NAMESPACE] remains the fallback namespace for workload creation when
     * a `NamespaceScope` doesn't override it, and every discovered profile/execution still
     * reports its own namespace. `"any"` requires the client's RBAC to permit cluster-wide
     * `list` on the involved resource types (`pods`, `jobs`, `podtemplates`).
     */
    @ElementDefaultAttribute("configured")
    const val NAMESPACE_DISCOVERY = "dev.getelements.conductor.kubernetes.namespace.discovery"

    /**
     * The job set name used to filter `PodTemplate`s. Only templates labelled
     * `namazu.conductor/job-set=<value>` matching this attribute are surfaced as profiles.
     * Defaults to `"default"`.
     */
    @ElementDefaultAttribute("default")
    const val JOBSET = "dev.getelements.conductor.kubernetes.job.set"

    /**
     * A friendly, human-readable name for this job set, shown in the admin panel wherever the raw
     * [JOBSET] value would otherwise be displayed. Defaults to `"default"`, matching [JOBSET]'s own
     * default so an unconfigured deployment still shows something sensible.
     */
    @ElementDefaultAttribute("default")
    const val JOBSET_NAME = "dev.getelements.conductor.kubernetes.job.set.name"

    /**
     * An optional Markdown description of this job set, rendered in the admin panel (Available Jobs /
     * Running Jobs pages) to give operators context about what it's for. Empty by default.
     */
    @ElementDefaultAttribute("")
    const val JOBSET_DESCRIPTION = "dev.getelements.conductor.kubernetes.job.set.description"

    /**
     * Optional path to a kubeconfig file. When empty, the Fabric8 client auto-detects configuration
     * (in-cluster service account, then `~/.kube/config`).
     */
    @ElementDefaultAttribute("")
    const val KUBECONFIG_PATH = "dev.getelements.conductor.kubernetes.kubeconfig.path"

    /**
     * Optional Kubernetes API server URL override. When empty, the URL is taken from the auto-detected
     * or kubeconfig-supplied configuration.
     */
    @ElementDefaultAttribute("")
    const val MASTER_URL = "dev.getelements.conductor.kubernetes.master.url"

    /**
     * The interval, in milliseconds, at which workload status is polled while awaiting a target status.
     * Defaults to `5000`.
     */
    @ElementDefaultAttribute("5000")
    const val POLL_INTERVAL = "dev.getelements.conductor.kubernetes.poll.interval.ms"

    /**
     * When `"true"`, workload status transitions are observed via a Kubernetes watch on the
     * underlying Pod/Job instead of polling every [POLL_INTERVAL] milliseconds. Defaults to
     * `"false"` to preserve the existing polling behaviour.
     */
    @ElementDefaultAttribute("false")
    const val WATCH_ENABLED = "dev.getelements.conductor.kubernetes.watch.enabled"

}