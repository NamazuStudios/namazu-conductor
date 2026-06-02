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
     * The job set name used to filter `PodTemplate`s. Only templates labelled
     * `namazu.conductor/job-set=<value>` matching this attribute are surfaced as profiles.
     * Defaults to `"default"`.
     */
    @ElementDefaultAttribute("default")
    const val JOBSET = "dev.getelements.conductor.kubernetes.job.set"

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

}