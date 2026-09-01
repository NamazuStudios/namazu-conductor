package dev.getelements.conductor.kubernetes.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.DaemonExecution
import dev.getelements.conductor.DaemonRequest
import dev.getelements.conductor.DaemonStatus
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobPlacement
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.NamespaceScope
import dev.getelements.conductor.RegionPlacement
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.exception.StdioUnavailableException
import dev.getelements.conductor.kubernetes.KubernetesAttributes
import dev.getelements.conductor.kubernetes.KubernetesDaemon
import dev.getelements.conductor.kubernetes.KubernetesExecutionDetails
import dev.getelements.conductor.kubernetes.KubernetesJobProfile
import dev.getelements.conductor.kubernetes.WorkloadKind
import dev.getelements.conductor.service.Daemon
import dev.getelements.conductor.service.DaemonOrchestrationService
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.ListOptionsBuilder
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.PodTemplate
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.ServicePort
import io.fabric8.kubernetes.api.model.ServicePortBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.PatchContext
import io.fabric8.kubernetes.client.dsl.base.PatchType
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * [OrchestrationService] and [DaemonOrchestrationService] implementation backed by Kubernetes via
 * the Fabric8 client.
 *
 * Profiles/daemons correspond to `PodTemplate` resources in the configured namespace, filtered by
 * the `namazu.conductor/job-set` label. A [JobRequest] is dispatched either as a bare `Pod`
 * (long-standing workloads) or a `batch/v1 Job` (one-off, run-to-completion workloads); a
 * [DaemonRequest] is deployed as a `Deployment` (persistent, horizontally-scaled workloads) — all
 * selected by the `namazu.conductor/workload-kind` annotation on the template. A `Service` is
 * created only when the template declares the `namazu.conductor/expose-ports` annotation; its type
 * defaults to `NodePort` and may be overridden via `namazu.conductor/service-type`. For daemons, a
 * `HorizontalPodAutoscaler` is additionally created when both `namazu.conductor/min-replicas` and
 * `namazu.conductor/max-replicas` are present.
 *
 * Only [RegionPlacement] is honoured (mapped to a `topology.kubernetes.io/zone` node selector); other
 * [dev.getelements.conductor.JobPlacement] types are silently ignored.
 *
 * A [NamespaceScope] on the request overrides the namespace the workload (and its `Service`, if
 * any) is created in; the `PodTemplate` backing the profile is still resolved from its own
 * namespace. Other [dev.getelements.conductor.JobScope] types are silently ignored.
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in
 * [KubernetesAttributes].
 */
@Singleton
class KubernetesOrchestrationService @Inject constructor(
    @Named(KubernetesAttributes.NAMESPACE) private val namespace: String,
    @Named(KubernetesAttributes.JOBSET) private val jobSet: String,
    @Named(KubernetesAttributes.POLL_INTERVAL) pollInterval: String,
    @Named(KubernetesAttributes.WATCH_ENABLED) watchEnabled: String = "false",
    private val client: KubernetesClient,
    private val executor: ExecutorService
) : OrchestrationService, DaemonOrchestrationService {

    private val logger = LoggerFactory.getLogger(KubernetesOrchestrationService::class.java)

    private val pollIntervalMs: Long = pollInterval.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_MS

    private val isWatchEnabled: Boolean = watchEnabled.toBoolean()

    /**
     * Returns one [KubernetesJobProfile] per `PodTemplate` in the configured namespace labelled
     * `namazu.conductor/job-set=<jobSet>`. Templates without a container, or whose `workload-kind` is
     * `daemon`, are skipped (see [getAvailableDaemons]).
     */
    override fun getAvailableProfiles(): List<JobProfile> =
        client.resources(PodTemplate::class.java)
            .inNamespace(namespace)
            .withLabel(LABEL_JOB_SET, jobSet)
            .list()
            .items
            .mapNotNull { toProfile(it) }

    private fun toProfile(template: PodTemplate): KubernetesJobProfile? {
        val container = template.template?.spec?.containers?.firstOrNull() ?: return null
        val annotations = template.metadata?.annotations ?: emptyMap()

        val kind = workloadKindOf(annotations)
        if (kind == WorkloadKind.DAEMON) return null

        val templateName = template.metadata?.name ?: return null

        return KubernetesJobProfile(
            namespace = template.metadata?.namespace ?: namespace,
            name = templateName,
            primaryContainer = container.name,
            workloadKind = kind,
            exposePorts = annotations[ANN_EXPOSE_PORTS] ?: "",
            serviceType = annotations[ANN_SERVICE_TYPE]?.trim()?.ifBlank { null } ?: DEFAULT_SERVICE_TYPE,
            ttlSecondsAfterFinished = parseIntAnnotation(templateName, annotations, ANN_TTL_SECONDS_AFTER_FINISHED),
            backoffLimit = parseIntAnnotation(templateName, annotations, ANN_BACKOFF_LIMIT),
            activeDeadlineSeconds = parseLongAnnotation(templateName, annotations, ANN_ACTIVE_DEADLINE_SECONDS),
            completions = parseIntAnnotation(templateName, annotations, ANN_COMPLETIONS),
            parallelism = parseIntAnnotation(templateName, annotations, ANN_PARALLELISM)
        )
    }

    /**
     * Returns one [KubernetesDaemon] per `PodTemplate` in the configured namespace labelled
     * `namazu.conductor/job-set=<jobSet>` whose `namazu.conductor/workload-kind` annotation is
     * `daemon`. Templates without a container, or whose `workload-kind` is not `daemon`, are skipped
     * (see [getAvailableProfiles]).
     */
    override fun getAvailableDaemons(): List<Daemon> =
        client.resources(PodTemplate::class.java)
            .inNamespace(namespace)
            .withLabel(LABEL_JOB_SET, jobSet)
            .list()
            .items
            .mapNotNull { toDaemon(it) }

    private fun toDaemon(template: PodTemplate): KubernetesDaemon? {
        val container = template.template?.spec?.containers?.firstOrNull() ?: return null
        val annotations = template.metadata?.annotations ?: emptyMap()

        if (workloadKindOf(annotations) != WorkloadKind.DAEMON) return null

        val templateName = template.metadata?.name ?: return null

        return KubernetesDaemon(
            namespace = template.metadata?.namespace ?: namespace,
            name = templateName,
            primaryContainer = container.name,
            exposePorts = annotations[ANN_EXPOSE_PORTS] ?: "",
            serviceType = annotations[ANN_SERVICE_TYPE]?.trim()?.ifBlank { null } ?: DEFAULT_SERVICE_TYPE,
            replicas = parseIntAnnotation(templateName, annotations, ANN_REPLICAS) ?: 1,
            minReplicas = parseIntAnnotation(templateName, annotations, ANN_MIN_REPLICAS),
            maxReplicas = parseIntAnnotation(templateName, annotations, ANN_MAX_REPLICAS),
            targetCpuUtilizationPercentage = parseIntAnnotation(templateName, annotations, ANN_TARGET_CPU_UTILIZATION_PERCENTAGE)
        )
    }

    private fun workloadKindOf(annotations: Map<String, String>): WorkloadKind =
        annotations[ANN_WORKLOAD_KIND]
            ?.let { runCatching { WorkloadKind.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?: WorkloadKind.POD

    /**
     * Creates a `Pod` or `Job` for the given [JobRequest] and returns a [JobExecution] with status
     * [JobStatus.PENDING]. The workload's name is derived from the profile name plus a short unique
     * suffix and is carried in the [JobExecution.id] as `"$namespace:$kind:$name"`. When the profile
     * declares ports to expose, a `Service` selecting the workload is created alongside it.
     *
     * @throws JobException if [JobRequest.profile] is not a [KubernetesJobProfile] or the underlying
     *   `PodTemplate` can no longer be found.
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? KubernetesJobProfile
            ?: throw JobException("JobProfile must be a ${KubernetesJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

        val template = client.resources(PodTemplate::class.java)
            .inNamespace(profile.namespace)
            .withName(profile.name)
            .get()
            ?: throw JobException("PodTemplate '${profile.name}' not found in namespace '${profile.namespace}'")

        val podTemplateSpec = template.template
            ?: throw JobException("PodTemplate '${profile.name}' has no pod template spec")

        val spec = podTemplateSpec.spec
            ?: throw JobException("PodTemplate '${profile.name}' has no pod spec")

        applyOverrides(spec, profile.primaryContainer, request.command, request.args, request.environment)
        applyPlacement(spec, request.placement)

        val namespace = request.scope.filterIsInstance<NamespaceScope>().firstOrNull()?.namespace
            ?: profile.namespace

        val runName = "${profile.name}-${UUID.randomUUID().toString().substring(0, 8)}"
        val templateLabels = podTemplateSpec.metadata?.labels ?: emptyMap()

        val ownerRef = when (profile.workloadKind) {
            WorkloadKind.POD -> {
                val pod = PodBuilder()
                    .withMetadata(
                        ObjectMetaBuilder()
                            .withName(runName)
                            .withNamespace(namespace)
                            .addToLabels(templateLabels)
                            .addToLabels(LABEL_OWNED_BY, runName)
                            .build()
                    )
                    .withSpec(spec)
                    .build()
                val created = client.pods().inNamespace(namespace).resource(pod).create()
                OwnerReferenceBuilder()
                    .withApiVersion("v1")
                    .withKind("Pod")
                    .withName(runName)
                    .withUid(created.metadata.uid)
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build()
            }
            WorkloadKind.JOB -> {
                val podMeta = ObjectMetaBuilder()
                    .addToLabels(templateLabels)
                    .addToLabels(LABEL_OWNED_BY, runName)
                    .build()
                val job = JobBuilder()
                    .withMetadata(
                        ObjectMetaBuilder()
                            .withName(runName)
                            .withNamespace(namespace)
                            .addToLabels(LABEL_OWNED_BY, runName)
                            .build()
                    )
                    .withNewSpec()
                    .apply {
                        if (profile.ttlSecondsAfterFinished != null) withTtlSecondsAfterFinished(profile.ttlSecondsAfterFinished)
                        if (profile.backoffLimit != null)            withBackoffLimit(profile.backoffLimit)
                        if (profile.activeDeadlineSeconds != null)   withActiveDeadlineSeconds(profile.activeDeadlineSeconds)
                        if (profile.completions != null)             withCompletions(profile.completions)
                        if (profile.parallelism != null)             withParallelism(profile.parallelism)
                    }
                    .withTemplate(
                        PodTemplateSpecBuilder().withMetadata(podMeta).withSpec(spec).build()
                    )
                    .endSpec()
                    .build()
                val created = client.batch().v1().jobs().inNamespace(namespace).resource(job).create()
                OwnerReferenceBuilder()
                    .withApiVersion("batch/v1")
                    .withKind("Job")
                    .withName(runName)
                    .withUid(created.metadata.uid)
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build()
            }
            WorkloadKind.DAEMON ->
                throw JobException("PodTemplate '${profile.name}' is workload-kind 'daemon'; use deploy() instead of execute()")
        }

        createServiceIfRequested(profile.exposePorts, profile.serviceType, namespace, runName, ownerRef)

        return JobExecution(
            id = encodeId(namespace, profile.workloadKind, runName),
            status = JobStatus.PENDING,
            details = KubernetesExecutionDetails(
                namespace = namespace,
                workloadKind = profile.workloadKind.name.lowercase(),
                name = runName
            )
        )
    }

    /**
     * Creates a `Deployment` (and, optionally, a `HorizontalPodAutoscaler`) for the given
     * [DaemonRequest] and returns a [DaemonExecution] with status [DaemonStatus.PENDING]. The
     * workload's name is derived from the profile name plus a short unique suffix and is carried in
     * the [DaemonExecution.id] as `"$namespace:daemon:$name"`. When the profile declares ports to
     * expose, a `Service` selecting the workload is created alongside it. An HPA is created only when
     * both [KubernetesDaemon.minReplicas] and [KubernetesDaemon.maxReplicas] are set.
     *
     * @throws JobException if [DaemonRequest.profile] is not a [KubernetesDaemon] or the underlying
     *   `PodTemplate` can no longer be found.
     */
    override fun deploy(request: DaemonRequest): DaemonExecution {
        val profile = request.profile as? KubernetesDaemon
            ?: throw JobException("Daemon must be a ${KubernetesDaemon::class.simpleName}; got ${request.profile::class.simpleName}")

        val template = client.resources(PodTemplate::class.java)
            .inNamespace(profile.namespace)
            .withName(profile.name)
            .get()
            ?: throw JobException("PodTemplate '${profile.name}' not found in namespace '${profile.namespace}'")

        val podTemplateSpec = template.template
            ?: throw JobException("PodTemplate '${profile.name}' has no pod template spec")

        val spec = podTemplateSpec.spec
            ?: throw JobException("PodTemplate '${profile.name}' has no pod spec")

        applyOverrides(spec, profile.primaryContainer, request.command, request.args, request.environment)
        applyPlacement(spec, request.placement)

        val namespace = request.scope.filterIsInstance<NamespaceScope>().firstOrNull()?.namespace
            ?: profile.namespace

        val runName = "${profile.name}-${UUID.randomUUID().toString().substring(0, 8)}"
        val templateLabels = podTemplateSpec.metadata?.labels ?: emptyMap()

        val podMeta = ObjectMetaBuilder()
            .addToLabels(templateLabels)
            .addToLabels(LABEL_OWNED_BY, runName)
            .build()

        val deployment = DeploymentBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(runName)
                    .withNamespace(namespace)
                    .addToLabels(LABEL_OWNED_BY, runName)
                    .build()
            )
            .withNewSpec()
                .withReplicas(profile.replicas)
                .withNewSelector()
                    .addToMatchLabels(LABEL_OWNED_BY, runName)
                .endSelector()
                .withTemplate(PodTemplateSpecBuilder().withMetadata(podMeta).withSpec(spec).build())
            .endSpec()
            .build()

        val created = client.apps().deployments().inNamespace(namespace).resource(deployment).create()

        val ownerRef = OwnerReferenceBuilder()
            .withApiVersion("apps/v1")
            .withKind("Deployment")
            .withName(runName)
            .withUid(created.metadata.uid)
            .withController(true)
            .withBlockOwnerDeletion(true)
            .build()

        createServiceIfRequested(profile.exposePorts, profile.serviceType, namespace, runName, ownerRef)

        if (profile.minReplicas != null && profile.maxReplicas != null) {
            createHpa(
                namespace,
                runName,
                ownerRef,
                profile.minReplicas,
                profile.maxReplicas,
                profile.targetCpuUtilizationPercentage ?: DEFAULT_TARGET_CPU_UTILIZATION_PERCENTAGE
            )
        }

        return DaemonExecution(
            id = encodeId(namespace, WorkloadKind.DAEMON, runName),
            status = DaemonStatus.PENDING,
            desiredCount = profile.replicas,
            runningCount = 0,
            minCount = profile.minReplicas,
            maxCount = profile.maxReplicas,
            details = KubernetesExecutionDetails(namespace = namespace, workloadKind = "daemon", name = runName)
        )
    }

    /**
     * Deletes the `Deployment` identified by [execution], any `HorizontalPodAutoscaler` and `Service`
     * the provider created for it.
     *
     * @throws JobException if the `Deployment` is not found (Fabric8 returns an empty result list
     *   rather than throwing on 404).
     */
    override fun undeploy(execution: DaemonExecution) {
        val (ns, kind, name) = decodeId(execution.id)
        requireDaemon(kind, name)

        runCatching {
            client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name).delete()
        }.onFailure { logger.warn("Failed to delete HorizontalPodAutoscaler '{}' in namespace '{}'", name, ns, it) }

        val deleted = client.apps().deployments().inNamespace(ns).withName(name)
            .withPropagationPolicy(DeletionPropagation.BACKGROUND)
            .delete()

        if (deleted.isEmpty()) {
            logger.warn("undeploy(): Deployment '{}' not found in namespace '{}' — nothing deleted", name, ns)
            throw JobException("Kubernetes deployment '$name' not found in namespace '$ns'")
        }

        logger.debug("undeploy(): deleted Deployment '{}' in namespace '{}'", name, ns)

        // Delete any owned Service. No-op when none was created.
        client.services().inNamespace(ns).withName(name).delete()
    }

    /**
     * Returns a fresh snapshot of [execution]'s status, derived from the live `Deployment` (and, if
     * present, `HorizontalPodAutoscaler` and `Service`) state. [DaemonStatus.RUNNING] requires ready
     * replicas to meet or exceed the desired count; [DaemonStatus.DEGRADED] covers a partially-ready
     * Deployment; [DaemonStatus.FAILED] covers a missing Deployment or a `Progressing=False`
     * condition (rare in practice, since Deployments retry indefinitely by design).
     */
    override fun getStatus(execution: DaemonExecution): DaemonExecution {
        val (ns, kind, name) = decodeId(execution.id)
        requireDaemon(kind, name)

        val deployment = client.apps().deployments().inNamespace(ns).withName(name).get()
            ?: return execution.copy(status = DaemonStatus.FAILED, runningCount = 0)

        val desired = deployment.spec?.replicas ?: 0
        val ready = deployment.status?.readyReplicas ?: 0

        val progressingFailed = deployment.status?.conditions.orEmpty()
            .any { it.type == "Progressing" && it.status == "False" }

        val status = when {
            progressingFailed -> DaemonStatus.FAILED
            ready >= desired && desired > 0 -> DaemonStatus.RUNNING
            ready == 0 -> DaemonStatus.PENDING
            else -> DaemonStatus.DEGRADED
        }

        val hpa = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name).get()

        val endpoints = client.services().inNamespace(ns).withName(name).get()
            ?.let { endpointsFromService(it) }
            ?: emptyList()

        return DaemonExecution(
            id = execution.id,
            status = status,
            desiredCount = desired,
            runningCount = ready,
            minCount = hpa?.spec?.minReplicas,
            maxCount = hpa?.spec?.maxReplicas,
            endpoints = endpoints,
            details = execution.details
        )
    }

    /**
     * Patches the `Deployment`'s replica count and returns a fresh [getStatus] snapshot. Note that an
     * active `HorizontalPodAutoscaler` may reassert its own desired count on its next reconcile if the
     * manually-set count doesn't match current scaling conditions — this is expected Kubernetes
     * behaviour, not a bug.
     */
    override fun setDesiredCount(execution: DaemonExecution, desired: Int): DaemonExecution {
        val (ns, kind, name) = decodeId(execution.id)
        requireDaemon(kind, name)

        // A merge patch built from a minimal object (rather than edit()'s diff-against-the-full
        // server object) avoids serializing the live Deployment's server-populated `managedFields`,
        // which trips a Jackson NPE in the Fabric8 model's any-getter serialization.
        val patch = DeploymentBuilder()
            .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
            .withNewSpec().withReplicas(desired).endSpec()
            .build()

        client.apps().deployments().inNamespace(ns).withName(name)
            .patch(PatchContext.of(PatchType.JSON_MERGE), patch)

        return getStatus(execution)
    }

    /**
     * Sets the autoscaling bounds for [execution]. If a `HorizontalPodAutoscaler` already exists for
     * this daemon, its bounds are patched in place; otherwise a new one is created (using the default
     * CPU utilization target, since none was configured at deploy time), retroactively enabling
     * autoscaling on a Deployment originally deployed without bounds. Returns a fresh [getStatus]
     * snapshot.
     */
    override fun setScalingBounds(execution: DaemonExecution, min: Int, max: Int): DaemonExecution {
        val (ns, kind, name) = decodeId(execution.id)
        requireDaemon(kind, name)

        val existing = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name).get()

        if (existing != null) {
            // See setDesiredCount() — a minimal-object merge patch avoids serializing the live
            // HPA's server-populated `managedFields`, which trips a Jackson NPE via edit().
            val patch = HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withName(name).withNamespace(ns).endMetadata()
                .withNewSpec()
                    .withMinReplicas(min)
                    .withMaxReplicas(max)
                .endSpec()
                .build()

            client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(ns).withName(name)
                .patch(PatchContext.of(PatchType.JSON_MERGE), patch)
        } else {
            val deployment = client.apps().deployments().inNamespace(ns).withName(name).get()
                ?: throw JobException("Kubernetes deployment '$name' not found in namespace '$ns'")

            val ownerRef = OwnerReferenceBuilder()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withName(name)
                .withUid(deployment.metadata.uid)
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build()

            createHpa(ns, name, ownerRef, min, max, DEFAULT_TARGET_CPU_UTILIZATION_PERCENTAGE)
        }

        return getStatus(execution)
    }

    private fun createHpa(
        namespace: String,
        name: String,
        ownerRef: OwnerReference,
        minReplicas: Int,
        maxReplicas: Int,
        targetCpuUtilizationPercentage: Int
    ) {
        val hpa = HorizontalPodAutoscalerBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(namespace)
                    .addToLabels(LABEL_OWNED_BY, name)
                    .addToOwnerReferences(ownerRef)
                    .build()
            )
            .withNewSpec()
                .withNewScaleTargetRef()
                    .withApiVersion("apps/v1")
                    .withKind("Deployment")
                    .withName(name)
                .endScaleTargetRef()
                .withMinReplicas(minReplicas)
                .withMaxReplicas(maxReplicas)
                .addNewMetric()
                    .withType("Resource")
                    .withNewResource()
                        .withName("cpu")
                        .withNewTarget()
                            .withType("Utilization")
                            .withAverageUtilization(targetCpuUtilizationPercentage)
                        .endTarget()
                    .endResource()
                .endMetric()
            .endSpec()
            .build()

        client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).resource(hpa).create()
    }

    private fun requireDaemon(kind: WorkloadKind, name: String) {
        if (kind != WorkloadKind.DAEMON) {
            throw JobException("Execution '$name' is not a daemon execution (kind=$kind)")
        }
    }

    override fun listExecutions(): List<JobExecution> {
        val executions = mutableListOf<JobExecution>()

        // Standalone pods: not owned by a batch Job, and not already terminating.
        // Terminating pods (deletionTimestamp set) are excluded so that the list reflects
        // the intended state immediately after stop() is called rather than waiting for the
        // full graceful-termination period (default 30 s) to elapse.
        client.pods().inNamespace(namespace).withLabel(LABEL_OWNED_BY).list().items
            .filter { pod -> pod.metadata?.ownerReferences?.any { it.kind == "Job" } != true }
            .filter { pod -> pod.metadata?.deletionTimestamp == null }
            .forEach { pod ->
                val name = pod.metadata?.name ?: return@forEach
                val id = encodeId(namespace, WorkloadKind.POD, name)
                val status = mapPodPhase(pod.status?.phase)
                executions += JobExecution(
                    id = id,
                    status = status,
                    endpoints = if (status == JobStatus.RUNNING) mapEndpoints(JobExecution(id = id, status = status)) else emptyList(),
                    details = KubernetesExecutionDetails(namespace = namespace, workloadKind = "pod", name = name)
                )
            }

        // Batch Jobs
        client.batch().v1().jobs().inNamespace(namespace).withLabel(LABEL_OWNED_BY).list().items
            .forEach { job ->
                val name = job.metadata?.name ?: return@forEach
                val id = encodeId(namespace, WorkloadKind.JOB, name)
                val status = mapJobStatus(namespace, name, job)
                executions += JobExecution(
                    id = id,
                    status = status,
                    endpoints = if (status == JobStatus.RUNNING) mapEndpoints(JobExecution(id = id, status = status)) else emptyList(),
                    details = KubernetesExecutionDetails(namespace = namespace, workloadKind = "job", name = name)
                )
            }

        return executions
    }

    /**
     * Resolves once [status] (or [JobStatus.FAILED]) is reached, populating [JobEndpoint]s once the
     * workload is [JobStatus.RUNNING]. When [KubernetesAttributes.WATCH_ENABLED] is `true`, the
     * transition is observed via a Kubernetes watch on the underlying Pod/Job; otherwise the workload
     * status is polled on a background thread every [pollIntervalMs].
     */
    override fun getFutureForStatus(execution: JobExecution, status: JobStatus): Future<JobExecution> =
        internalFutureForStatus(execution, status)

    override fun getStageForStatus(execution: JobExecution, status: JobStatus): CompletionStage<JobExecution> =
        internalFutureForStatus(execution, status)

    private fun internalFutureForStatus(execution: JobExecution, status: JobStatus): CompletableFuture<JobExecution> =
        if (isWatchEnabled) watchFutureForStatus(execution, status)
        else CompletableFuture.supplyAsync({ pollForStatus(execution, status) }, executor)

    private fun pollForStatus(execution: JobExecution, status: JobStatus): JobExecution {
        var result: JobExecution
        do {
            Thread.sleep(pollIntervalMs)
            result = resultFor(execution, currentStatus(execution))
        } while (result.status != status && result.status != JobStatus.FAILED)
        return result
    }

    /**
     * Watches the Pod/Job backing [execution] and completes the returned future as soon as [target]
     * (or [JobStatus.FAILED]) is observed, rather than waiting for the next poll tick. The watch is
     * started from the resourceVersion of the object fetched to compute the initial status, so a
     * transition landing between that fetch and the watch registering is still delivered as an event
     * rather than silently missed. Falls back to [pollForStatus] if the watch closes with an error
     * before a terminal status is reached.
     */
    private fun watchFutureForStatus(execution: JobExecution, target: JobStatus): CompletableFuture<JobExecution> {
        val future = CompletableFuture<JobExecution>()
        val (ns, kind, name) = decodeId(execution.id)

        fun completeIfTerminal(current: JobStatus) {
            if (!future.isDone && (current == target || current == JobStatus.FAILED)) {
                future.complete(resultFor(execution, current))
            }
        }

        val watch: Watch = when (kind) {
            WorkloadKind.POD -> {
                val resource = client.pods().inNamespace(ns).withName(name)
                val pod = resource.get()
                completeIfTerminal(if (pod == null) JobStatus.FAILED else mapPodPhase(pod.status?.phase))
                if (future.isDone) return future

                val options = ListOptionsBuilder().withResourceVersion(pod?.metadata?.resourceVersion).build()
                resource.watch(options, object : Watcher<Pod> {
                    override fun eventReceived(action: Watcher.Action, resource: Pod) =
                        completeIfTerminal(mapPodPhase(resource.status?.phase))

                    override fun onClose(cause: WatcherException?) {
                        if (!future.isDone) fallBackToPoll(future, execution, target, cause)
                    }
                })
            }
            WorkloadKind.JOB -> {
                val resource = client.batch().v1().jobs().inNamespace(ns).withName(name)
                val job = resource.get()
                completeIfTerminal(if (job == null) JobStatus.FAILED else mapJobStatus(ns, name, job))
                if (future.isDone) return future

                val options = ListOptionsBuilder().withResourceVersion(job?.metadata?.resourceVersion).build()
                resource.watch(options, object : Watcher<Job> {
                    override fun eventReceived(action: Watcher.Action, resource: Job) =
                        completeIfTerminal(mapJobStatus(ns, name, resource))

                    override fun onClose(cause: WatcherException?) {
                        if (!future.isDone) fallBackToPoll(future, execution, target, cause)
                    }
                })
            }
            WorkloadKind.DAEMON ->
                throw JobException("Execution '$name' is a daemon; use getStatus() instead of getFutureForStatus()")
        }

        future.whenComplete { _, _ -> watch.close() }
        return future
    }

    private fun fallBackToPoll(
        future: CompletableFuture<JobExecution>,
        execution: JobExecution,
        target: JobStatus,
        cause: WatcherException?
    ) {
        logger.warn(
            "watch for execution '{}' closed before reaching status {} — falling back to polling ({})",
            execution.id, target, cause?.message
        )
        CompletableFuture.supplyAsync({ pollForStatus(execution, target) }, executor)
            .whenComplete { result, error ->
                if (error != null) future.completeExceptionally(error) else future.complete(result)
            }
    }

    private fun resultFor(execution: JobExecution, status: JobStatus): JobExecution = JobExecution(
        id = execution.id,
        status = status,
        endpoints = if (status == JobStatus.RUNNING) mapEndpoints(execution) else emptyList()
    )

    /**
     * Deletes the workload identified by [execution] and any Service the provider created for it.
     *
     * @throws dev.getelements.conductor.exception.JobException if the workload is not found
     *   (Fabric8 returns an empty result list rather than throwing on 404).
     */
    override fun stop(execution: JobExecution) {
        val (ns, kind, name) = decodeId(execution.id)

        val deleted = when (kind) {
            WorkloadKind.POD -> client.pods().inNamespace(ns).withName(name).delete()
            WorkloadKind.JOB -> client.batch().v1().jobs().inNamespace(ns).withName(name)
                .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                .delete()
            WorkloadKind.DAEMON ->
                throw JobException("Execution '$name' is a daemon; use undeploy() instead of stop()")
        }

        if (deleted.isEmpty()) {
            logger.warn("stop(): {} '{}' not found in namespace '{}' — nothing deleted", kind, name, ns)
            throw JobException(
                "Kubernetes ${kind.name.lowercase()} '$name' not found in namespace '$ns'"
            )
        }

        logger.debug("stop(): deleted {} '{}' in namespace '{}'", kind, name, ns)

        // Delete any owned Service. No-op when none was created.
        client.services().inNamespace(ns).withName(name).delete()
    }

    /**
     * Opens a live, bidirectional stdio session on the Pod backing [execution] (resolved via
     * [locatePod] for a Job), via Kubernetes exec/attach — the same mechanism as `kubectl attach`.
     * Defaults to the workload's first container when it has more than one, mirroring how [toProfile]
     * chooses the profile's primary container.
     *
     * Attach requires the container to actually be running; short-lived Jobs are typically no longer
     * attachable by the time a caller gets around to streaming their stdio (use [getFutureForStatus]
     * / [getStageForStatus] with [JobStatus.RUNNING] to know when it's safe to call this).
     *
     * @throws StdioUnavailableException if the Pod can't be found, or isn't currently `Running`
     */
    override fun streamStdio(execution: JobExecution): JobStdio {
        val (ns, kind, name) = decodeId(execution.id)

        val podName = when (kind) {
            WorkloadKind.POD -> name
            WorkloadKind.JOB -> locatePod(ns, name)?.metadata?.name
                ?: throw StdioUnavailableException(
                    "No pod found for Job '$name' in namespace '$ns' — job may not have started yet"
                )
            WorkloadKind.DAEMON ->
                throw UnsupportedOperationException("${this::class.simpleName} does not support stdio streaming for daemon executions")
        }

        val resource = client.pods().inNamespace(ns).withName(podName)
        val pod = resource.get()
            ?: throw StdioUnavailableException("Pod '$podName' not found in namespace '$ns'")

        val phase = pod.status?.phase
        if (phase != "Running") {
            throw StdioUnavailableException(
                "Pod '$podName' is not Running (phase='$phase') — stdio attach requires a running process"
            )
        }

        val containerName = pod.spec?.containers?.firstOrNull()?.name
        val containerResource = if (containerName != null) resource.inContainer(containerName) else resource

        val execWatch = containerResource
            .redirectingInput()
            .redirectingOutput()
            .redirectingError()
            .attach()

        return JobStdio(
            stdin = execWatch.input,
            stdout = execWatch.output,
            stderr = execWatch.error,
            onClose = execWatch::close
        )
    }

    private fun currentStatus(execution: JobExecution): JobStatus {
        val (ns, kind, name) = decodeId(execution.id)
        return when (kind) {
            WorkloadKind.POD -> {
                val pod = client.pods().inNamespace(ns).withName(name).get()
                    ?: return JobStatus.FAILED
                mapPodPhase(pod.status?.phase)
            }
            WorkloadKind.JOB -> {
                val job = client.batch().v1().jobs().inNamespace(ns).withName(name).get()
                    ?: return JobStatus.FAILED
                mapJobStatus(ns, name, job)
            }
            WorkloadKind.DAEMON ->
                throw JobException("Execution '$name' is a daemon; use getStatus() instead of getFutureForStatus()")
        }
    }

    private fun mapJobStatus(ns: String, name: String, job: Job): JobStatus {
        val jobStatus = job.status
        return when {
            (jobStatus?.succeeded ?: 0) >= 1 -> JobStatus.COMPLETED
            jobStatus?.conditions?.any { it.type == "Failed" && it.status == "True" } == true -> JobStatus.FAILED
            else -> mapPodPhase(locatePod(ns, name)?.status?.phase)
        }
    }

    private fun mapEndpoints(execution: JobExecution): List<JobEndpoint> {
        val (ns, kind, name) = decodeId(execution.id)

        val service = client.services().inNamespace(ns).withName(name).get()
        if (service != null) return endpointsFromService(service)

        val pod = when (kind) {
            WorkloadKind.POD -> client.pods().inNamespace(ns).withName(name).get()
            WorkloadKind.JOB -> locatePod(ns, name)
            WorkloadKind.DAEMON -> null
        } ?: return emptyList()

        val host = pod.status?.podIP ?: return emptyList()
        return pod.spec?.containers.orEmpty().flatMap { container ->
            container.ports.orEmpty().map { port ->
                JobEndpoint(
                    host = host,
                    port = port.containerPort,
                    protocol = (port.protocol ?: "TCP").lowercase()
                )
            }
        }
    }

    private fun endpointsFromService(service: Service): List<JobEndpoint> {
        val type = service.spec?.type ?: DEFAULT_SERVICE_TYPE
        val host = when (type) {
            "LoadBalancer" -> service.status?.loadBalancer?.ingress?.firstOrNull()?.let { it.ip ?: it.hostname }
            "NodePort" -> firstNodeAddress()
            else -> service.spec?.clusterIP
        } ?: return emptyList()

        return service.spec?.ports.orEmpty().map { servicePort ->
            val port = if (type == "NodePort") (servicePort.nodePort ?: servicePort.port) else servicePort.port
            JobEndpoint(
                host = host,
                port = port,
                protocol = (servicePort.protocol ?: "TCP").lowercase()
            )
        }
    }

    private fun firstNodeAddress(): String? {
        val addresses = client.nodes().list().items.firstOrNull()?.status?.addresses ?: return null
        return addresses.firstOrNull { it.type == "ExternalIP" }?.address
            ?: addresses.firstOrNull { it.type == "InternalIP" }?.address
    }

    private fun locatePod(ns: String, jobName: String): Pod? =
        client.pods().inNamespace(ns).withLabel(LABEL_OWNED_BY, jobName).list().items.firstOrNull()

    private fun createServiceIfRequested(
        exposePorts: String,
        serviceType: String,
        namespace: String,
        runName: String,
        ownerRef: OwnerReference
    ) {
        val ports = parseExposePorts(exposePorts)
        if (ports.isEmpty()) return

        val servicePorts: List<ServicePort> = ports.map { (port, protocol) ->
            ServicePortBuilder()
                .withName("port-$port-${protocol.lowercase()}")
                .withPort(port)
                .withNewTargetPort(port)
                .withProtocol(protocol)
                .build()
        }

        val service = ServiceBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(runName)
                    .withNamespace(namespace)
                    .addToLabels(LABEL_OWNED_BY, runName)
                    .addToOwnerReferences(ownerRef)
                    .build()
            )
            .withNewSpec()
            .withType(serviceType)
            .addToSelector(LABEL_OWNED_BY, runName)
            .withPorts(servicePorts)
            .endSpec()
            .build()

        client.services().inNamespace(namespace).resource(service).create()
    }

    private fun applyOverrides(
        spec: PodSpec,
        primaryContainer: String,
        command: List<String>,
        args: List<String>,
        environment: Map<String, String>
    ) {
        val container = spec.containers.firstOrNull { it.name == primaryContainer }
            ?: throw JobException("Container '$primaryContainer' not found in PodTemplate spec")

        if (command.isNotEmpty()) container.command = command
        if (args.isNotEmpty()) container.args = args

        if (environment.isNotEmpty()) {
            val merged = container.env.orEmpty().associateBy { it.name }.toMutableMap()
            environment.forEach { (key, value) -> merged[key] = EnvVar(key, value, null) }
            container.env = merged.values.toList()
        }
    }

    private fun applyPlacement(spec: PodSpec, placement: List<JobPlacement>) {
        val region = placement.filterIsInstance<RegionPlacement>().firstOrNull() ?: return
        val selector = spec.nodeSelector.orEmpty().toMutableMap()
        selector[ZONE_LABEL] = region.id
        spec.nodeSelector = selector
    }

    private fun parseIntAnnotation(templateName: String, annotations: Map<String, String>, key: String): Int? {
        val raw = annotations[key] ?: return null
        val parsed = raw.trim().toIntOrNull()
        return if (parsed != null && parsed >= 0) parsed
        else { logger.warn("PodTemplate '{}' has invalid {}: '{}' — omitting field", templateName, key, raw); null }
    }

    private fun parseLongAnnotation(templateName: String, annotations: Map<String, String>, key: String): Long? {
        val raw = annotations[key] ?: return null
        val parsed = raw.trim().toLongOrNull()
        return if (parsed != null && parsed >= 0) parsed
        else { logger.warn("PodTemplate '{}' has invalid {}: '{}' — omitting field", templateName, key, raw); null }
    }

    private fun parseExposePorts(raw: String): List<Pair<Int, String>> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val parts = entry.split("/", limit = 2)
                val port = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val protocol = parts.getOrNull(1)?.trim()?.uppercase()?.ifBlank { null } ?: "TCP"
                port to protocol
            }

    private fun mapPodPhase(phase: String?): JobStatus = when (phase) {
        "Running" -> JobStatus.RUNNING
        "Succeeded" -> JobStatus.COMPLETED
        "Failed" -> JobStatus.FAILED
        else -> JobStatus.PENDING
    }

    private fun encodeId(namespace: String, kind: WorkloadKind, name: String): String =
        "$namespace:${kind.name.lowercase()}:$name"

    private fun decodeId(id: String): Triple<String, WorkloadKind, String> {
        val parts = id.split(":", limit = 3)
        if (parts.size != 3) throw JobException("Malformed Kubernetes job execution id: '$id'")
        val kind = runCatching { WorkloadKind.valueOf(parts[1].uppercase()) }.getOrNull()
            ?: throw JobException("Unknown workload kind in execution id: '$id'")
        return Triple(parts[0], kind, parts[2])
    }

    companion object {

        private const val DEFAULT_POLL_INTERVAL_MS = 5_000L

        private const val DEFAULT_SERVICE_TYPE = "NodePort"

        private const val DEFAULT_TARGET_CPU_UTILIZATION_PERCENTAGE = 80

        const val LABEL_JOB_SET = "namazu.conductor/job-set"

        const val LABEL_OWNED_BY = "namazu.conductor/owned-by"

        const val ANN_WORKLOAD_KIND = "namazu.conductor/workload-kind"

        const val ANN_EXPOSE_PORTS = "namazu.conductor/expose-ports"

        const val ANN_SERVICE_TYPE = "namazu.conductor/service-type"

        const val ANN_TTL_SECONDS_AFTER_FINISHED = "namazu.conductor/ttl-seconds-after-finished"

        const val ANN_BACKOFF_LIMIT = "namazu.conductor/backoff-limit"

        const val ANN_ACTIVE_DEADLINE_SECONDS = "namazu.conductor/active-deadline-seconds"

        const val ANN_COMPLETIONS = "namazu.conductor/completions"

        const val ANN_PARALLELISM = "namazu.conductor/parallelism"

        const val ANN_REPLICAS = "namazu.conductor/replicas"

        const val ANN_MIN_REPLICAS = "namazu.conductor/min-replicas"

        const val ANN_MAX_REPLICAS = "namazu.conductor/max-replicas"

        const val ANN_TARGET_CPU_UTILIZATION_PERCENTAGE = "namazu.conductor/target-cpu-utilization-percentage"

        const val ZONE_LABEL = "topology.kubernetes.io/zone"

    }

}
