package dev.getelements.conductor.kubernetes

import dev.getelements.conductor.ContainerRef
import dev.getelements.conductor.service.Daemon

/**
 * [Daemon] implementation for Kubernetes. Represents a single `PodTemplate` resource discovered in
 * the configured namespace, tagged `namazu.conductor/workload-kind: daemon`.
 *
 * The [id] is `"$namespace:$templateName"`. [primaryContainer] is the name of the first container in the
 * template's pod spec and is the target of command, argument, and environment overrides at deploy
 * time; [containers] lists every container in the template's pod spec, in pod-spec order, with the
 * first entry marked [ContainerRef.primary] (mirroring [KubernetesJobProfile.containers], and the
 * namespace [dev.getelements.conductor.DaemonRequest.containerEnvironment] keys against).
 * [exposePorts] holds the raw `namazu.conductor/expose-ports` annotation value (empty when
 * absent, in which case no Service is created); [serviceType] holds the
 * `namazu.conductor/service-type` annotation value (defaulting to `"NodePort"`). [replicas] is the
 * fixed/starting replica count (`namazu.conductor/replicas`, default `1`). [minReplicas]/[maxReplicas]
 * (`namazu.conductor/min-replicas`/`max-replicas`) are both required to be present for a
 * `HorizontalPodAutoscaler` to be created at deploy time; [targetCpuUtilizationPercentage]
 * (`namazu.conductor/target-cpu-utilization-percentage`, default `80`) only takes effect when an HPA
 * is created.
 */
data class KubernetesDaemon(
    val namespace: String,
    /** The `PodTemplate`'s resource name — distinct from [Daemon.name], the friendly display name. */
    val templateName: String,
    val primaryContainer: String,
    override val containers: List<ContainerRef>,
    val exposePorts: String,
    val serviceType: String,
    val replicas: Int = 1,
    val minReplicas: Int? = null,
    val maxReplicas: Int? = null,
    val targetCpuUtilizationPercentage: Int? = null,
    /**
     * The `namazu.conductor/display-name` annotation — the friendly name the admin dashboard shows in
     * place of the raw [id]. `null` when the annotation is absent; callers fall back to [id].
     */
    override val name: String? = null,
    /**
     * The `namazu.conductor/description` annotation — Markdown, surfaced to the admin dashboard.
     * `null` when the annotation is absent.
     */
    override val description: String? = null,
    /**
     * The `PodTemplate`'s **entire** top-level annotation map, verbatim and unfiltered, exactly as
     * for [KubernetesJobProfile.metadata]. Conductor assigns no meaning to any of them; see
     * [dev.getelements.conductor.Metadata].
     */
    override val metadata: Map<String, String> = emptyMap()
) : Daemon {

    override val id: String
        get() = "$namespace:$templateName"

}
