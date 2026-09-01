package dev.getelements.conductor.kubernetes.service

import dev.getelements.conductor.DaemonExecution
import dev.getelements.conductor.DaemonRequest
import dev.getelements.conductor.DaemonStatus
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_EXPOSE_PORTS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_MAX_REPLICAS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_MIN_REPLICAS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_REPLICAS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_SERVICE_TYPE
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_WORKLOAD_KIND
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_JOB_SET
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodTemplate
import io.fabric8.kubernetes.api.model.PodTemplateBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.slf4j.LoggerFactory
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNull
import org.testng.Assert.assertThrows
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Integration test for [KubernetesOrchestrationService]'s [dev.getelements.conductor.service.DaemonOrchestrationService]
 * implementation, run against a local **minikube** cluster (also used in GitHub CI). The test creates
 * its own `PodTemplate`s (a fixed-replica daemon and an autoscaled daemon), exercises `deploy()`,
 * `setDesiredCount()`, `setScalingBounds()`, `getStatus()`, and `undeploy()`, then deletes everything
 * it created.
 *
 * The test does **not** start or provision a cluster — one must already be running before
 * `mvn verify` (start minikube locally; the CI workflow provisions it). The suite **always runs**
 * and never skips: with no reachable cluster the Fabric8 calls fail and the suite fails.
 *
 * Shares the same `KUBERNETES_IT_*` environment variables as [KubernetesOrchestrationServiceIT] for
 * namespace/jobset/connectivity configuration; see that class's KDoc for the full list.
 */
class KubernetesDaemonOrchestrationServiceIT {

    private val logger = LoggerFactory.getLogger(KubernetesDaemonOrchestrationServiceIT::class.java)

    private lateinit var namespace: String
    private lateinit var jobSet: String
    private lateinit var client: KubernetesClient
    private lateinit var executor: ExecutorService
    private lateinit var service: KubernetesOrchestrationService

    private lateinit var podImage: String
    private lateinit var podArgs: List<String>

    private val fixedTemplate get() = "conductor-it-daemon-fixed-$runSuffix"
    private val autoscaledTemplate get() = "conductor-it-daemon-hpa-$runSuffix"
    private val unboundedTemplate get() = "conductor-it-daemon-unbounded-$runSuffix"
    private lateinit var runSuffix: String

    private var podPort: Int = 8080
    private var podProtocol: String = "tcp"
    private var timeoutMinutes: Long = 5

    private val executions = mutableListOf<DaemonExecution>()

    @BeforeClass
    fun setUp() {
        namespace = env("KUBERNETES_IT_NAMESPACE", "conductor-it")
        jobSet = env("KUBERNETES_IT_JOBSET", "default")
        podPort = env("KUBERNETES_IT_POD_PORT", "8080").toInt()
        podProtocol = env("KUBERNETES_IT_POD_PROTOCOL", "tcp")
        timeoutMinutes = env("KUBERNETES_IT_TIMEOUT_MINUTES", "5").toLong()
        podImage = env("KUBERNETES_IT_POD_IMAGE", "hashicorp/http-echo")
        podArgs = env("KUBERNETES_IT_POD_ARGS", "-listen=:8080,-text=conductor-ok")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        runSuffix = System.getenv("KUBERNETES_IT_RUN_ID") ?: System.nanoTime().toString().takeLast(6)

        client = buildClient()
        executor = Executors.newCachedThreadPool()
        service = KubernetesOrchestrationService(
            namespace = namespace,
            jobSet = jobSet,
            pollInterval = "3000",
            watchEnabled = "false",
            client = client,
            executor = executor
        )

        ensureNamespace()
        createDaemonTemplate(fixedTemplate, replicas = 2, minReplicas = null, maxReplicas = null)
        createDaemonTemplate(autoscaledTemplate, replicas = 1, minReplicas = 1, maxReplicas = 3)
        createDaemonTemplate(unboundedTemplate, replicas = 1, minReplicas = null, maxReplicas = null)

        logger.info(
            "Created daemon PodTemplates in namespace '{}': {}, {}, {}",
            namespace, fixedTemplate, autoscaledTemplate, unboundedTemplate
        )
    }

    @AfterClass(alwaysRun = true)
    fun tearDown() {
        if (::service.isInitialized) {
            executions.forEach { execution ->
                runCatching { service.undeploy(execution) }
                    .onFailure { logger.warn("Failed to undeploy execution {}", execution.id, it) }
            }
        }

        // Never delete the namespace itself here, even if this class created it: the namespace is
        // shared with KubernetesOrchestrationServiceIT (same default KUBERNETES_IT_NAMESPACE), which
        // may run in the same failsafe suite before or after this class, and deleting a namespace
        // out from under a sibling test class racing against it causes spurious
        // "namespace is being terminated" failures. Deleting only the templates this class created
        // is sufficient; the namespace itself is harmless to leave behind on an ephemeral CI cluster.
        if (::client.isInitialized) {
            listOf(fixedTemplate, autoscaledTemplate, unboundedTemplate).forEach { name ->
                runCatching { client.resources(PodTemplate::class.java).inNamespace(namespace).withName(name).delete() }
                    .onFailure { logger.warn("Failed to delete PodTemplate '{}'", name, it) }
            }
        }

        if (::executor.isInitialized) executor.shutdownNow()
        if (::client.isInitialized) client.close()
    }

    @Test
    fun discoversDaemonProfile() {
        val daemonIds = service.getAvailableDaemons().map { it.id }.toSet()
        assertTrue(daemonIds.contains("$namespace:$fixedTemplate"), "Fixed daemon not discovered; found: $daemonIds")

        val jobProfileIds = service.getAvailableProfiles().map { it.id }.toSet()
        assertFalse(
            jobProfileIds.contains("$namespace:$fixedTemplate"),
            "Daemon template leaked into getAvailableProfiles(): $jobProfileIds"
        )
    }

    @Test
    fun deployReachesRunningWithExpectedReplicaCount() {
        val profile = service.findAvailableDaemon("$namespace:$fixedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$fixedTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile)).also { executions += it }
        val running = awaitStatus(execution, DaemonStatus.RUNNING)

        assertEquals(running.status, DaemonStatus.RUNNING, "Daemon '$fixedTemplate' did not reach RUNNING")
        assertEquals(running.runningCount, 2, "Expected 2 running replicas")
        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint once RUNNING")
    }

    @Test
    fun setDesiredCountScalesReplicas() {
        val profile = service.findAvailableDaemon("$namespace:$fixedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$fixedTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile)).also { executions += it }
        awaitStatus(execution, DaemonStatus.RUNNING)

        val scaled = service.setDesiredCount(execution, 3)
        assertEquals(scaled.desiredCount, 3, "setDesiredCount did not update desiredCount")

        val running = awaitRunningCount(execution, 3)
        assertEquals(running.runningCount, 3, "Deployment did not scale to 3 running replicas")
    }

    @Test
    fun deployWithAutoscalingCreatesHpaAndHonoursBounds() {
        val profile = service.findAvailableDaemon("$namespace:$autoscaledTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$autoscaledTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile)).also { executions += it }
        awaitStatus(execution, DaemonStatus.RUNNING)

        val (_, _, name) = decodeIdForTest(execution.id)
        val hpa = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).withName(name).get()
        assertTrue(hpa != null, "Expected an HPA to exist for autoscaled daemon '$autoscaledTemplate'")
        assertEquals(hpa!!.spec.minReplicas, 1)
        assertEquals(hpa.spec.maxReplicas, 3)

        val status = service.getStatus(execution)
        assertEquals(status.minCount, 1)
        assertEquals(status.maxCount, 3)
    }

    @Test
    fun setScalingBoundsAddsHpaToUnboundedDaemon() {
        val profile = service.findAvailableDaemon("$namespace:$unboundedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$unboundedTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile)).also { executions += it }
        awaitStatus(execution, DaemonStatus.RUNNING)

        val (_, _, name) = decodeIdForTest(execution.id)
        val beforeHpa = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).withName(name).get()
        assertNull(beforeHpa, "Expected no HPA before setScalingBounds()")

        val updated = service.setScalingBounds(execution, 1, 2)
        assertEquals(updated.minCount, 1)
        assertEquals(updated.maxCount, 2)

        val afterHpa = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).withName(name).get()
        assertTrue(afterHpa != null, "Expected an HPA to exist after setScalingBounds()")
    }

    @Test
    fun setScalingBoundsUpdatesExistingHpa() {
        val profile = service.findAvailableDaemon("$namespace:$autoscaledTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$autoscaledTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile)).also { executions += it }
        awaitStatus(execution, DaemonStatus.RUNNING)

        val updated = service.setScalingBounds(execution, 2, 4)
        assertEquals(updated.minCount, 2)
        assertEquals(updated.maxCount, 4)

        val (_, _, name) = decodeIdForTest(execution.id)
        val hpa = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).withName(name).get()
        assertEquals(hpa!!.spec.minReplicas, 2)
        assertEquals(hpa.spec.maxReplicas, 4)
    }

    @Test
    fun undeployDeletesDeploymentServiceAndHpa() {
        val profile = service.findAvailableDaemon("$namespace:$autoscaledTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$autoscaledTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile))
        awaitStatus(execution, DaemonStatus.RUNNING)

        val (_, _, name) = decodeIdForTest(execution.id)
        service.undeploy(execution)

        val deployment: Deployment? = client.apps().deployments().inNamespace(namespace).withName(name).get()
        val hpa: HorizontalPodAutoscaler? = client.autoscaling().v2().horizontalPodAutoscalers().inNamespace(namespace).withName(name).get()
        val svc = client.services().inNamespace(namespace).withName(name).get()

        assertNull(deployment, "Deployment '$name' should have been deleted")
        assertNull(hpa, "HorizontalPodAutoscaler '$name' should have been deleted")
        assertNull(svc, "Service '$name' should have been deleted")
    }

    @Test
    fun undeployThrowsWhenNotFound() {
        val profile = service.findAvailableDaemon("$namespace:$fixedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$fixedTemplate' not found")

        val execution = service.deploy(DaemonRequest(profile = profile))
        service.undeploy(execution)

        assertThrows(JobException::class.java) { service.undeploy(execution) }
    }

    private fun awaitStatus(execution: DaemonExecution, target: DaemonStatus): DaemonExecution {
        val deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos()
        var latest = execution
        while (System.nanoTime() < deadline) {
            latest = service.getStatus(execution)
            if (latest.status == target || latest.status == DaemonStatus.FAILED) return latest
            Thread.sleep(3_000)
        }
        return latest
    }

    private fun awaitRunningCount(execution: DaemonExecution, count: Int): DaemonExecution {
        val deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos()
        var latest = execution
        while (System.nanoTime() < deadline) {
            latest = service.getStatus(execution)
            if (latest.runningCount == count) return latest
            Thread.sleep(3_000)
        }
        return latest
    }

    private fun decodeIdForTest(id: String): Triple<String, String, String> {
        val parts = id.split(":", limit = 3)
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun buildClient(): KubernetesClient {
        val context = System.getenv("KUBERNETES_IT_CONTEXT")
        val kubeconfigPath = System.getenv("KUBERNETES_IT_KUBECONFIG")

        val config: Config = if (!kubeconfigPath.isNullOrBlank()) {
            Config.fromKubeconfig(context, File(kubeconfigPath).readText(), kubeconfigPath)
        } else {
            Config.autoConfigure(context)
        }

        System.getenv("KUBERNETES_IT_MASTER_URL")?.takeIf { it.isNotBlank() }?.let { config.masterUrl = it }

        return KubernetesClientBuilder().withConfig(config).build()
    }

    private fun ensureNamespace() {
        if (client.namespaces().withName(namespace).get() == null) {
            client.namespaces()
                .resource(NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
                .create()
            logger.info("Created namespace '{}'", namespace)
        }
    }

    private fun createDaemonTemplate(name: String, replicas: Int, minReplicas: Int?, maxReplicas: Int?) {
        val container = ContainerBuilder()
            .withName("server")
            .withImage(podImage)
            .apply { if (podArgs.isNotEmpty()) withArgs(podArgs) }
            .addNewPort()
                .withContainerPort(podPort)
                .withProtocol(podProtocol.uppercase())
            .endPort()
            .build()

        val template = PodTemplateBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(LABEL_JOB_SET, jobSet)
                .addToAnnotations(ANN_WORKLOAD_KIND, "daemon")
                .addToAnnotations(ANN_EXPOSE_PORTS, "$podPort/$podProtocol")
                .addToAnnotations(ANN_SERVICE_TYPE, "NodePort")
                .addToAnnotations(ANN_REPLICAS, replicas.toString())
                .apply { if (minReplicas != null) addToAnnotations(ANN_MIN_REPLICAS, minReplicas.toString()) }
                .apply { if (maxReplicas != null) addToAnnotations(ANN_MAX_REPLICAS, maxReplicas.toString()) }
            .endMetadata()
            .withNewTemplate()
                .withNewSpec()
                    .withContainers(container)
                .endSpec()
            .endTemplate()
            .build()
        client.resources(PodTemplate::class.java).inNamespace(namespace).resource(template).create()
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

}
