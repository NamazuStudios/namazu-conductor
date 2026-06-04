package dev.getelements.conductor.kubernetes

import dev.getelements.conductor.service.JobProfile

/**
 * [JobProfile] implementation for Kubernetes. Represents a single `PodTemplate` resource discovered in
 * the configured namespace.
 *
 * The [id] is `"$namespace:$name"`. [primaryContainer] is the name of the first container in the
 * template's pod spec and is the target of command, argument, and environment overrides at execution
 * time. [workloadKind] is derived from the `namazu.conductor/workload-kind` annotation (defaulting to
 * [WorkloadKind.POD]); [exposePorts] holds the raw `namazu.conductor/expose-ports` annotation value
 * (empty when absent, in which case no Service is created); [serviceType] holds the
 * `namazu.conductor/service-type` annotation value (defaulting to `"NodePort"`).
 */
data class KubernetesJobProfile(
    val namespace: String,
    val name: String,
    val primaryContainer: String,
    val workloadKind: WorkloadKind,
    val exposePorts: String,
    val serviceType: String
) : JobProfile {

    override val id: String
        get() = "$namespace:$name"

}