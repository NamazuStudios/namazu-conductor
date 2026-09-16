package dev.getelements.conductor.kubernetes

import dev.getelements.conductor.ContainerRef
import dev.getelements.conductor.service.JobProfile

/**
 * [JobProfile] implementation for Kubernetes. Represents a single `PodTemplate` resource discovered in
 * the configured namespace.
 *
 * The [id] is `"$namespace:$name"`. [primaryContainer] is the name of the first container in the
 * template's pod spec and is the target of command, argument, and environment overrides at execution
 * time; [containers] lists every container in the template's pod spec, in pod-spec order, with the
 * first entry marked [ContainerRef.primary]. [workloadKind] is derived from the
 * `namazu.conductor/workload-kind` annotation (defaulting to [WorkloadKind.POD]); [exposePorts] holds
 * the raw `namazu.conductor/expose-ports` annotation value (empty when absent, in which case no
 * Service is created); [serviceType] holds the `namazu.conductor/service-type` annotation value
 * (defaulting to `"NodePort"`).
 */
data class KubernetesJobProfile(
    val namespace: String,
    val name: String,
    val primaryContainer: String,
    override val containers: List<ContainerRef>,
    val workloadKind: WorkloadKind,
    val exposePorts: String,
    val serviceType: String,
    /** `namazu.conductor/ttl-seconds-after-finished` → `spec.ttlSecondsAfterFinished` */
    val ttlSecondsAfterFinished: Int? = null,
    /** `namazu.conductor/backoff-limit` → `spec.backoffLimit` */
    val backoffLimit: Int? = null,
    /** `namazu.conductor/active-deadline-seconds` → `spec.activeDeadlineSeconds` */
    val activeDeadlineSeconds: Long? = null,
    /** `namazu.conductor/completions` → `spec.completions` */
    val completions: Int? = null,
    /** `namazu.conductor/parallelism` → `spec.parallelism` */
    val parallelism: Int? = null,
    /** `namazu.conductor/terminal-job` — hints that this profile should default to an interactive tty */
    override val terminalJob: Boolean = false,
    /** `namazu.conductor/description` — Markdown, surfaced to the admin dashboard */
    override val description: String? = null
) : JobProfile {

    override val id: String
        get() = "$namespace:$name"

}