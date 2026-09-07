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
    DAEMON

}
