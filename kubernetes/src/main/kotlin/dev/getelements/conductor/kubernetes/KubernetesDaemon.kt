package dev.getelements.conductor.kubernetes

import dev.getelements.conductor.service.Daemon

/**
 * [Daemon] implementation for Kubernetes. Represents a single `PodTemplate` resource discovered in
 * the configured namespace, tagged `namazu.conductor/workload-kind: daemon`.
 *
 * The [id] is `"$namespace:$name"`. [primaryContainer] is the name of the first container in the
 * template's pod spec and is the target of command, argument, and environment overrides at deploy
 * time. [exposePorts] holds the raw `namazu.conductor/expose-ports` annotation value (empty when
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
    val name: String,
    val primaryContainer: String,
    val exposePorts: String,
    val serviceType: String,
    val replicas: Int = 1,
    val minReplicas: Int? = null,
    val maxReplicas: Int? = null,
    val targetCpuUtilizationPercentage: Int? = null
) : Daemon {

    override val id: String
        get() = "$namespace:$name"

}
