package dev.getelements.conductor.kubernetes

/**
 * The kind of Kubernetes workload a [KubernetesJobProfile] or [KubernetesDaemon] produces when
 * executed/deployed. Declared on the source `PodTemplate` via the `namazu.conductor/workload-kind`
 * annotation, mirroring how the ECS provider derives launch type from a task-definition tag.
 */
enum class WorkloadKind {

    /**
     * A long-standing workload backed by a bare `Pod`. Pod phase maps directly onto
     * [dev.getelements.conductor.JobStatus]. This is the default when the annotation is absent.
     */
    POD,

    /**
     * A one-off, run-to-completion workload backed by a `batch/v1 Job`. Completion is tracked on the
     * Job; while active, the underlying pod supplies running status and endpoints.
     */
    JOB,

    /**
     * A persistent, horizontally-scaled workload backed by a `Deployment` (and, optionally, a
     * `HorizontalPodAutoscaler`). Unlike [POD]/[JOB], there is no completion — see
     * [dev.getelements.conductor.DaemonStatus].
     */
    DAEMON,

    /**
     * A workload created and torn down by a Helm release this provider did **not** create — e.g. a
     * caller that `helm install`s something itself, purely for admin-panel visibility/killability.
     * Discovered the same way as [POD] (a live-queried marker `Pod` carrying `namazu.conductor/owned-by`),
     * distinguished by also carrying a `namazu.conductor/helm-release` annotation naming the release
     * to act on. `execute()` cannot dispatch this kind (there is no chart/values to install from a
     * `PodTemplate` alone); `stop()` runs `helm uninstall` against the named release instead of
     * deleting the marker Pod directly, so the whole release (Services, PVCs, everything the chart
     * created) is torn down, not just the one Pod conductor happens to see.
     */
    HELM

}
