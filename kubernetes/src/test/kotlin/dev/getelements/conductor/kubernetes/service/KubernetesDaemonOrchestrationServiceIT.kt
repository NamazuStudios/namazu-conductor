package dev.getelements.conductor.kubernetes.service

import dev.getelements.conductor.DaemonExecution
import dev.getelements.conductor.DaemonRequest
import dev.getelements.conductor.DaemonStatus
import dev.getelements.conductor.SecretRef
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.exception.ReservedMetadataKeyException
import dev.getelements.conductor.exception.UnknownContainerException
import dev.getelements.conductor.kubernetes.service.KubernetesSecretStore
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_EXPOSE_PORTS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_MAX_REPLICAS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_MIN_REPLICAS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_REPLICAS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_SERVICE_TYPE
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_WORKLOAD_KIND
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_JOB_SET
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_OWNED_BY
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodTemplate
import io.fabric8.kubernetes.api.model.PodTemplateBuilder
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import org.slf4j.LoggerFactory
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
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

    companion object {
        /** Non-`namazu.conductor` annotation keys, standing in for arbitrary infrastructure metadata. */
        private const val METADATA_OWNER_KEY = "docs.example.com/owner"
        private const val METADATA_NOTE_KEY = "run.example.com/note"

        /** A cosmetic reserved key — overridable per run, unlike the behavioural keys. */
        private const val METADATA_HIDDEN_KEY = "namazu.conductor/hidden"
    }

    private val logger = LoggerFactory.getLogger(KubernetesDaemonOrchestrationServiceIT::class.java)

    private lateinit var namespace: String
    private lateinit var jobSet: String
    private lateinit var client: KubernetesClient
    private lateinit var executor: ExecutorService
    private lateinit var service: KubernetesOrchestrationService
    private lateinit var secretStore: KubernetesSecretStore

    private lateinit var podImage: String
    private lateinit var podArgs: List<String>

    private val fixedTemplate get() = "conductor-it-daemon-fixed-$runSuffix"
    private val autoscaledTemplate get() = "conductor-it-daemon-hpa-$runSuffix"
    private val unboundedTemplate get() = "conductor-it-daemon-unbounded-$runSuffix"
    private val secretName get() = "conductor-it-daemon-secret-$runSuffix"
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
        secretStore = KubernetesSecretStore(client, namespace)
        service = KubernetesOrchestrationService(
            namespace = namespace,
            jobSet = jobSet,
            jobSetName = jobSet,
            jobSetDescription = "",
            pollInterval = "3000",
            watchEnabled = "false",
            kubeconfigPath = System.getenv("KUBERNETES_IT_KUBECONFIG") ?: "",
            client = client,
            executor = executor,
            secretStore = secretStore
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
        if (::secretStore.isInitialized) {
            runCatching { secretStore.delete(secretName) }
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

    /**
     * Daemon metadata mirrors job metadata: the `PodTemplate`'s whole annotation map is declared
     * verbatim, a caller's overrides merge over it, and the `Deployment`'s pod template is both the
     * write target and the read-back source -- so a later [KubernetesOrchestrationService.getStatus]
     * reports what actually landed rather than what was requested.
     */
    @Test
    fun metadataIsDeclaredVerbatimMergedWithOverridesAndReadBack() {
        val profile = service.findAvailableDaemon("$namespace:$fixedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$fixedTemplate' not found")

        assertEquals(
            profile.metadata[METADATA_OWNER_KEY], "platform-team",
            "Expected a non-Conductor annotation to be declared verbatim"
        )
        assertEquals(
            profile.metadata[ANN_REPLICAS], "2",
            "Expected the namazu.conductor/replicas key to be declared verbatim, not stripped"
        )

        val deploymentsBefore = client.apps().deployments().inNamespace(namespace).list().items.size
        assertThrows(ReservedMetadataKeyException::class.java) {
            service.deploy(DaemonRequest(profile = profile, metadata = mapOf(ANN_REPLICAS to "9")))
        }
        assertEquals(
            client.apps().deployments().inNamespace(namespace).list().items.size, deploymentsBefore,
            "A rejected metadata override must not have created a Deployment"
        )

        val execution = service.deploy(DaemonRequest(
            profile = profile,
            metadata = mapOf(
                METADATA_OWNER_KEY to "sre-oncall", METADATA_NOTE_KEY to "pinned",
                METADATA_HIDDEN_KEY to "true"
            )
        )).also { executions += it }
        val running = awaitStatus(execution, DaemonStatus.RUNNING)

        assertEquals(running.metadata[METADATA_OWNER_KEY], "sre-oncall", "Override did not win")
        assertEquals(running.metadata[METADATA_NOTE_KEY], "pinned", "New key was not added")
        assertEquals(running.metadata[ANN_REPLICAS], "2", "Unmentioned declared key was not preserved")
        assertEquals(running.metadata[METADATA_HIDDEN_KEY], "true", "Cosmetic reserved override was not accepted")

        val (_, _, name) = decodeIdForTest(running.id)
        val onCluster = client.apps().deployments().inNamespace(namespace).withName(name).get()
            ?.spec?.template?.metadata?.annotations.orEmpty()
        assertEquals(onCluster[METADATA_OWNER_KEY], "sre-oncall", "Override did not land on the Deployment")
        assertEquals(onCluster[METADATA_NOTE_KEY], "pinned", "New key did not land on the Deployment")
        assertEquals(onCluster[METADATA_HIDDEN_KEY], "true", "Cosmetic reserved override did not land on the Deployment")

        // getStatus() re-reads from the cluster rather than echoing the daemon we were handed.
        val refreshed = service.getStatus(running)
        assertEquals(refreshed.metadata[METADATA_OWNER_KEY], "sre-oncall", "getStatus() lost the metadata")
        assertEquals(refreshed.metadata[METADATA_NOTE_KEY], "pinned", "getStatus() lost the new key")
    }

    /**
     * Runtime annotations added to a Deployment's **top level** — where callers annotate at runtime
     * (e.g. the agent's `kubectl annotate deployment/…` link publication, or the agent-proxy's
     * `namazu.ade/status` readiness patch) — surface in
     * [KubernetesOrchestrationService.getStatus]'s read-back, merged **unfiltered** over the inner
     * `spec.template` block: any top-level annotation is reported verbatim, consistently with the
     * declared template block and the standalone-pod row (originally #67, which first surfaced the
     * top-level annotations at all — a `namazu.conductor`-only overlay filter subsequently proved
     * to be a silent-drop trap and was removed, mirroring the Job-path test in
     * [KubernetesOrchestrationServiceIT]).
     */
    @Test
    fun deploymentTopLevelAnnotationsSurfaceInReadBack() {
        val profile = service.findAvailableDaemon("$namespace:$unboundedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$unboundedTemplate' not found")

        val execution = service.deploy(DaemonRequest(
            profile = profile, metadata = mapOf(METADATA_HIDDEN_KEY to "true")
        )).also { executions += it }
        awaitStatus(execution, DaemonStatus.RUNNING)

        val (_, _, name) = decodeIdForTest(execution.id)
        annotateDeploymentTopLevel(name, mapOf(
            "namazu.conductor/link.QuantumREST" to "https://example.internal/quantum/api/",
            METADATA_NOTE_KEY to "annotated at runtime"
        ))

        val refreshed = service.getStatus(execution)
        assertEquals(
            refreshed.metadata["namazu.conductor/link.QuantumREST"], "https://example.internal/quantum/api/",
            "A runtime link published on the Deployment's top level was not surfaced in the read-back"
        )
        assertEquals(refreshed.metadata[METADATA_HIDDEN_KEY], "true", "The declared set from the template block was lost")
        assertEquals(
            refreshed.metadata[METADATA_NOTE_KEY], "annotated at runtime",
            "A non-namazu.conductor top-level annotation must be read back verbatim"
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

    /**
     * A [SecretRef] on a daemon deploys the same way as on a job (issue #84): the Deployment's pod
     * template carries a `valueFrom.secretKeyRef` env source (never a literal value), the secret is
     * copied into the namespace under a deployment-owned name, and undeploying garbage-collects
     * the copy via its ownerReference.
     */
    @Test
    fun deployWithSecretRefWiresSecretKeyRefAndCopiesSecret() {
        secretStore.store(secretName, mapOf("token" to "s3cr3t-value"))
        try {
            val profile = service.findAvailableDaemon("$namespace:$fixedTemplate")
                ?: throw AssertionError("Daemon profile '$namespace:$fixedTemplate' not found")

            val execution = service.deploy(DaemonRequest(
                profile = profile,
                secrets = listOf(SecretRef(name = secretName, envKey = "CONDUCTOR_IT_SECRET")),
            )).also { executions += it }
            awaitStatus(execution, DaemonStatus.RUNNING)

            val (_, _, name) = decodeIdForTest(execution.id)
            val deployment = client.apps().deployments().inNamespace(namespace).withName(name).get()
                ?: throw AssertionError("Deployment '$name' not found after deploy()")
            val env = deployment.spec?.template?.spec?.containers?.first()?.env.orEmpty()
            val injected = env.firstOrNull { it.name == "CONDUCTOR_IT_SECRET" }
            assertNotNull(injected, "The SecretRef's env source is missing from the pod template")
            assertNull(injected!!.value, "The secret's value must never be embedded in the spec")
            val keyRef = injected.valueFrom?.secretKeyRef
            assertNotNull(keyRef, "The env source must be a secretKeyRef")
            assertEquals("$name-$secretName", keyRef!!.name)
            assertEquals("token", keyRef.key)

            val copy = client.secrets().inNamespace(namespace).withName("$name-$secretName").get()
            assertNotNull(copy, "The secret was not copied into the launch namespace")
            assertEquals(name, copy!!.metadata.labels[LABEL_OWNED_BY])

            service.undeploy(execution)
            executions.remove(execution)
            repeat(30) {
                if (client.secrets().inNamespace(namespace).withName("$name-$secretName").get() == null) return
                Thread.sleep(1000)
            }
            throw AssertionError("Secret copy '$name-$secretName' was not garbage-collected with the deployment")
        } finally {
            runCatching { secretStore.delete(secretName) }
        }
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

    /**
     * Per-container env, daemon parity with the job path (issue #73): `containerEnvironment` merges
     * into the named container of the `Deployment`'s pod template, the flat `environment` keeps
     * targeting the primary, and an unknown container name is refused before any Deployment exists.
     */
    @Test
    fun containerEnvironmentMergesIntoDeploymentPodTemplate() {
        val profile = service.findAvailableDaemon("$namespace:$fixedTemplate")
            ?: throw AssertionError("Daemon profile '$namespace:$fixedTemplate' not found")

        val deploymentsBefore = client.apps().deployments().inNamespace(namespace).list().items.size
        assertThrows(UnknownContainerException::class.java) {
            service.deploy(DaemonRequest(
                profile = profile,
                containerEnvironment = mapOf("no-such-container" to mapOf("A" to "b")),
            ))
        }
        assertEquals(
            client.apps().deployments().inNamespace(namespace).list().items.size, deploymentsBefore,
            "A rejected containerEnvironment entry must not have created a Deployment"
        )

        val execution = service.deploy(DaemonRequest(
            profile = profile,
            environment = mapOf("CONDUCTOR_IT_FLAT" to "flat"),
            containerEnvironment = mapOf("server" to mapOf("CONDUCTOR_IT_QUALIFIED" to "qualified")),
        )).also { executions += it }
        awaitStatus(execution, DaemonStatus.RUNNING)

        val (_, _, name) = decodeIdForTest(execution.id)
        val server = client.apps().deployments().inNamespace(namespace).withName(name).get()
            ?.spec?.template?.spec?.containers.orEmpty()
            .first { it.name == "server" }
        val env = server.env.orEmpty().associate { it.name to it.value }
        assertEquals(env["CONDUCTOR_IT_FLAT"], "flat", "Flat environment did not reach the primary container")
        assertEquals(env["CONDUCTOR_IT_QUALIFIED"], "qualified", "Per-container entry did not reach the named container")
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

    /**
     * Adds [annotations] to a Deployment's **top-level** `metadata.annotations`, retrying on 409
     * Conflict: the deployment controller mutates the object concurrently (status bookkeeping), so a
     * single replace can race it — each attempt re-fetches the latest version, which settles the
     * conflict.
     */
    private fun annotateDeploymentTopLevel(deploymentName: String, annotations: Map<String, String>) {
        repeat(5) { attempt ->
            try {
                client.apps().deployments().inNamespace(namespace).withName(deploymentName).edit { deployment ->
                    DeploymentBuilder(deployment).editMetadata().apply {
                        annotations.forEach { (k, v) -> addToAnnotations(k, v) }
                    }.endMetadata().build()
                }
                return
            } catch (e: KubernetesClientException) {
                if (e.code != 409) throw e
                logger.info("PATCH conflict annotating deployment '{}' (attempt {}); retrying", deploymentName, attempt + 1)
                Thread.sleep(1000)
            }
        }
        throw AssertionError("Could not annotate deployment '$deploymentName' — 409 Conflict did not settle after retries")
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
        // Namespace deletion is async: a namespace that still exists may be mid-termination (e.g.
        // deleted by a previous run on the same cluster), and creating resources into it fails with
        // a 403 "NamespaceTerminating". Wait for any pending termination to fully complete first.
        repeat(30) { attempt ->
            val phase = client.namespaces().withName(namespace).get()?.status?.phase
            if (phase == "Terminating") {
                logger.info("Namespace '{}' is Terminating; waiting before recreating (attempt {})", namespace, attempt)
                Thread.sleep(1000)
            } else {
                if (phase == null) {
                    client.namespaces()
                        .resource(NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
                        .create()
                    logger.info("Created namespace '{}'", namespace)
                }
                return
            }
        }
        throw IllegalStateException("Namespace '$namespace' did not finish terminating within 30s")
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
                .addToAnnotations(METADATA_OWNER_KEY, "platform-team")
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
