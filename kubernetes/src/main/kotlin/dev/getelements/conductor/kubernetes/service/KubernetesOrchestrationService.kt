package dev.getelements.conductor.kubernetes.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.RegionPlacement
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.kubernetes.KubernetesAttributes
import dev.getelements.conductor.kubernetes.KubernetesJobProfile
import dev.getelements.conductor.kubernetes.WorkloadKind
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
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
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * [OrchestrationService] implementation backed by Kubernetes via the Fabric8 client.
 *
 * Profiles correspond to `PodTemplate` resources in the configured namespace, filtered by the
 * `namazu.conductor/job-set` label. A [JobRequest] is dispatched either as a bare `Pod` (long-standing
 * workloads) or a `batch/v1 Job` (one-off, run-to-completion workloads), selected by the
 * `namazu.conductor/workload-kind` annotation on the template. A `Service` is created only when the
 * template declares the `namazu.conductor/expose-ports` annotation; its type defaults to `NodePort`
 * and may be overridden via `namazu.conductor/service-type`.
 *
 * Only [RegionPlacement] is honoured (mapped to a `topology.kubernetes.io/zone` node selector); other
 * [dev.getelements.conductor.JobPlacement] types are silently ignored.
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in
 * [KubernetesAttributes].
 */
@Singleton
class KubernetesOrchestrationService @Inject constructor(
    @Named(KubernetesAttributes.NAMESPACE) private val namespace: String,
    @Named(KubernetesAttributes.JOBSET) private val jobSet: String,
    @Named(KubernetesAttributes.POLL_INTERVAL) pollInterval: String,
    private val client: KubernetesClient,
    private val executor: ExecutorService
) : OrchestrationService {

    private val pollIntervalMs: Long = pollInterval.toLongOrNull() ?: DEFAULT_POLL_INTERVAL_MS

    /**
     * Returns one [KubernetesJobProfile] per `PodTemplate` in the configured namespace labelled
     * `namazu.conductor/job-set=<jobSet>`. Templates without a container are skipped.
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

        val kind = annotations[ANN_WORKLOAD_KIND]
            ?.let { runCatching { WorkloadKind.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?: WorkloadKind.POD

        return KubernetesJobProfile(
            namespace = template.metadata?.namespace ?: namespace,
            name = template.metadata?.name ?: return null,
            primaryContainer = container.name,
            workloadKind = kind,
            exposePorts = annotations[ANN_EXPOSE_PORTS] ?: "",
            serviceType = annotations[ANN_SERVICE_TYPE]?.trim()?.ifBlank { null } ?: DEFAULT_SERVICE_TYPE
        )
    }

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

        applyOverrides(spec, profile, request)
        applyPlacement(spec, request)

        val runName = "${profile.name}-${UUID.randomUUID().toString().substring(0, 8)}"
        val templateLabels = podTemplateSpec.metadata?.labels ?: emptyMap()

        val ownerRef = when (profile.workloadKind) {
            WorkloadKind.POD -> {
                val pod = PodBuilder()
                    .withMetadata(
                        ObjectMetaBuilder()
                            .withName(runName)
                            .withNamespace(profile.namespace)
                            .addToLabels(templateLabels)
                            .addToLabels(LABEL_OWNED_BY, runName)
                            .build()
                    )
                    .withSpec(spec)
                    .build()
                val created = client.pods().inNamespace(profile.namespace).resource(pod).create()
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
                            .withNamespace(profile.namespace)
                            .addToLabels(LABEL_OWNED_BY, runName)
                            .build()
                    )
                    .withNewSpec()
                    .withTemplate(
                        PodTemplateSpecBuilder().withMetadata(podMeta).withSpec(spec).build()
                    )
                    .endSpec()
                    .build()
                val created = client.batch().v1().jobs().inNamespace(profile.namespace).resource(job).create()
                OwnerReferenceBuilder()
                    .withApiVersion("batch/v1")
                    .withKind("Job")
                    .withName(runName)
                    .withUid(created.metadata.uid)
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build()
            }
        }

        createServiceIfRequested(profile, runName, ownerRef)

        return JobExecution(id = encodeId(profile.namespace, profile.workloadKind, runName), status = JobStatus.PENDING)
    }

    /**
     * Polls workload status on a background thread until [status] is reached (or [JobStatus.FAILED]),
     * populating [JobEndpoint]s once the workload is [JobStatus.RUNNING].
     */
    override fun getFutureForStatus(
        execution: JobExecution,
        status: JobStatus
    ): Future<JobExecution> = CompletableFuture.supplyAsync({
        var result: JobExecution
        do {
            Thread.sleep(pollIntervalMs)
            val current = currentStatus(execution)
            result = JobExecution(
                id = execution.id,
                status = current,
                endpoints = if (current == JobStatus.RUNNING) mapEndpoints(execution) else emptyList()
            )
        } while (result.status != status && result.status != JobStatus.FAILED)
        result
    }, executor)

    /**
     * Deletes the workload identified by [execution] and any Service the provider created for it.
     */
    override fun stop(execution: JobExecution) {
        val (ns, kind, name) = decodeId(execution.id)

        when (kind) {
            WorkloadKind.POD -> client.pods().inNamespace(ns).withName(name).delete()
            WorkloadKind.JOB -> client.batch().v1().jobs().inNamespace(ns).withName(name)
                .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.BACKGROUND)
                .delete()
        }

        // Delete any owned Service. No-op when none was created.
        client.services().inNamespace(ns).withName(name).delete()
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
        profile: KubernetesJobProfile,
        runName: String,
        ownerRef: io.fabric8.kubernetes.api.model.OwnerReference
    ) {
        val ports = parseExposePorts(profile.exposePorts)
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
                    .withNamespace(profile.namespace)
                    .addToLabels(LABEL_OWNED_BY, runName)
                    .addToOwnerReferences(ownerRef)
                    .build()
            )
            .withNewSpec()
            .withType(profile.serviceType)
            .addToSelector(LABEL_OWNED_BY, runName)
            .withPorts(servicePorts)
            .endSpec()
            .build()

        client.services().inNamespace(profile.namespace).resource(service).create()
    }

    private fun applyOverrides(spec: PodSpec, profile: KubernetesJobProfile, request: JobRequest) {
        val container = spec.containers.firstOrNull { it.name == profile.primaryContainer }
            ?: throw JobException("Container '${profile.primaryContainer}' not found in PodTemplate '${profile.name}'")

        if (request.command.isNotEmpty()) container.command = request.command
        if (request.args.isNotEmpty()) container.args = request.args

        if (request.environment.isNotEmpty()) {
            val merged = container.env.orEmpty().associateBy { it.name }.toMutableMap()
            request.environment.forEach { (key, value) -> merged[key] = EnvVar(key, value, null) }
            container.env = merged.values.toList()
        }
    }

    private fun applyPlacement(spec: PodSpec, request: JobRequest) {
        val region = request.placement.filterIsInstance<RegionPlacement>().firstOrNull() ?: return
        val selector = spec.nodeSelector.orEmpty().toMutableMap()
        selector[ZONE_LABEL] = region.id
        spec.nodeSelector = selector
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

        const val LABEL_JOB_SET = "namazu.conductor/job-set"

        const val LABEL_OWNED_BY = "namazu.conductor/owned-by"

        const val ANN_WORKLOAD_KIND = "namazu.conductor/workload-kind"

        const val ANN_EXPOSE_PORTS = "namazu.conductor/expose-ports"

        const val ANN_SERVICE_TYPE = "namazu.conductor/service-type"

        const val ZONE_LABEL = "topology.kubernetes.io/zone"

    }

}