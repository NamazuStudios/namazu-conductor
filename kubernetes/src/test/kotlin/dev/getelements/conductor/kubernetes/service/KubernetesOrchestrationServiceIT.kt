package dev.getelements.conductor.kubernetes.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.exception.StdioUnavailableException
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_EXPOSE_PORTS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_SERVICE_TYPE
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_WORKLOAD_KIND
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_JOB_SET
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_OWNED_BY
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodTemplate
import io.fabric8.kubernetes.api.model.PodTemplateBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.slf4j.LoggerFactory
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertThrows
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration test for [KubernetesOrchestrationService], run against a local **minikube** cluster
 * (also used in GitHub CI). The test creates its own `PodTemplate`s, exercises the service across
 * the `NodePort`, `LoadBalancer`, and one-off `Job` paths, and deletes everything it created.
 *
 * The test does **not** start or provision a cluster — one must already be running before
 * `mvn verify` (start minikube locally; the CI workflow provisions it). The suite **always runs**
 * and never skips: with no reachable cluster the Fabric8 calls fail and the suite fails.
 *
 * **Local prerequisites (start these first):**
 * ```
 * ./kubernetes/start-minikube.sh   # starts minikube + tunnel (or run `minikube start` / `minikube tunnel` by hand)
 * mvn verify -pl kubernetes -am    # in another terminal
 * ```
 * Connectivity uses Fabric8 auto-detection (`~/.kube/config`, which `minikube start` writes); the
 * namespace defaults to `conductor-it` and is created/destroyed by the test.
 *
 * **Environment variables (all optional):**
 *
 * | Variable | Default | Purpose |
 * |---|---|---|
 * | `KUBERNETES_IT_NAMESPACE`       | `conductor-it` | Namespace for templates/workloads; created if absent |
 * | `KUBERNETES_IT_JOBSET`          | `default` | Value for the `namazu.conductor/job-set` label/filter |
 * | `KUBERNETES_IT_KUBECONFIG`      | auto-detect | Path to a kubeconfig file |
 * | `KUBERNETES_IT_CONTEXT`         | current-context | kubeconfig context name |
 * | `KUBERNETES_IT_MASTER_URL`      | from config | API server URL override |
 * | `KUBERNETES_IT_POD_IMAGE`       | `hashicorp/http-echo` | Image for the server pod tests (serves on 8080) |
 * | `KUBERNETES_IT_POD_PORT`        | `8080` | Container port exposed by the server pod tests (non-privileged) |
 * | `KUBERNETES_IT_POD_ARGS`        | `-listen=:8080,-text=conductor-ok` | Comma-separated container args |
 * | `KUBERNETES_IT_POD_PROTOCOL`    | `tcp` | Protocol for the exposed port |
 * | `KUBERNETES_IT_HTTP_CHECK`      | `true` | If `true`, HTTP GET the resolved endpoint and assert a response |
 * | `KUBERNETES_IT_HTTP_PATH`       | `/` | Path used by the HTTP check |
 * | `KUBERNETES_IT_JOB_IMAGE`       | `busybox:stable` | Image for the one-off job test |
 * | `KUBERNETES_IT_JOB_COMMAND`     | `sh,-c,echo hello-from-conductor` | Comma-separated command for the job test |
 * | `KUBERNETES_IT_TIMEOUT_MINUTES` | `5` | Per-status / endpoint-resolution wait timeout |
 * | `KUBERNETES_IT_WATCH_ENABLED`   | `false` | Exercises the watch-based [KubernetesOrchestrationService.getFutureForStatus] path instead of polling |
 */
class KubernetesOrchestrationServiceIT {

    companion object {
        private const val MULTI_CONTAINER_PRIMARY = "primary"
        private const val MULTI_CONTAINER_SECONDARY = "secondary"
    }

    private val logger = LoggerFactory.getLogger(KubernetesOrchestrationServiceIT::class.java)

    private lateinit var namespace: String
    private lateinit var jobSet: String
    private lateinit var client: KubernetesClient
    private lateinit var executor: ExecutorService
    private lateinit var service: KubernetesOrchestrationService

    private lateinit var podImage: String
    private lateinit var podArgs: List<String>
    private lateinit var jobImage: String
    private lateinit var jobCommand: List<String>

    private val nodePortTemplate get() = "conductor-it-nodeport-$runSuffix"
    private val loadBalancerTemplate get() = "conductor-it-lb-$runSuffix"
    private val jobTemplate get() = "conductor-it-job-$runSuffix"
    private val multiContainerTemplate get() = "conductor-it-multi-$runSuffix"
    private lateinit var runSuffix: String

    private var podPort: Int = 80
    private var podProtocol: String = "tcp"
    private var httpCheck: Boolean = true
    private var httpPath: String = "/"
    private var timeoutMinutes: Long = 5

    private var createdNamespace: Boolean = false
    private val executions = mutableListOf<JobExecution>()

    /** Second namespace created by the discovery=any test, deleted in teardown. Null when unused. */
    private var altNamespaceCreated: String? = null

    @BeforeClass
    fun setUp() {
        namespace = env("KUBERNETES_IT_NAMESPACE", "conductor-it")
        jobSet = env("KUBERNETES_IT_JOBSET", "default")
        podPort = env("KUBERNETES_IT_POD_PORT", "8080").toInt()
        podProtocol = env("KUBERNETES_IT_POD_PROTOCOL", "tcp")
        httpCheck = env("KUBERNETES_IT_HTTP_CHECK", "true").toBoolean()
        httpPath = env("KUBERNETES_IT_HTTP_PATH", "/")
        timeoutMinutes = env("KUBERNETES_IT_TIMEOUT_MINUTES", "5").toLong()
        // A tiny HTTP server on a non-privileged port (8080). On Linux `minikube tunnel` still needs
        // sudo to add the LoadBalancer route; 8080 just avoids the extra privileged-port escalation.
        podImage = env("KUBERNETES_IT_POD_IMAGE", "hashicorp/http-echo")
        podArgs = env("KUBERNETES_IT_POD_ARGS", "-listen=:8080,-text=conductor-ok")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        jobImage = env("KUBERNETES_IT_JOB_IMAGE", "busybox:stable")
        jobCommand = env("KUBERNETES_IT_JOB_COMMAND", "sh,-c,echo hello-from-conductor")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        runSuffix = System.getenv("KUBERNETES_IT_RUN_ID") ?: System.nanoTime().toString().takeLast(6)

        client = buildClient()
        executor = Executors.newCachedThreadPool()
        service = KubernetesOrchestrationService(
            namespace = namespace,
            jobSet = jobSet,
            jobSetName = jobSet,
            jobSetDescription = "",
            pollInterval = "3000",
            watchEnabled = env("KUBERNETES_IT_WATCH_ENABLED", "false"),
            kubeconfigPath = System.getenv("KUBERNETES_IT_KUBECONFIG") ?: "",
            client = client,
            executor = executor
        )

        ensureNamespace()
        createServerTemplate(nodePortTemplate, "NodePort")
        createServerTemplate(loadBalancerTemplate, "LoadBalancer")
        createJobTemplate(jobTemplate)
        createMultiContainerTemplate(multiContainerTemplate)

        logger.info(
            "Created PodTemplates in namespace '{}': {}, {}, {}, {}",
            namespace, nodePortTemplate, loadBalancerTemplate, jobTemplate, multiContainerTemplate
        )
    }

    @AfterClass(alwaysRun = true)
    fun tearDown() {
        if (::service.isInitialized) {
            executions.forEach { execution ->
                runCatching { service.stop(execution) }
                    .onFailure { logger.warn("Failed to stop execution {}", execution.id, it) }
            }
        }

        if (::client.isInitialized) {
            if (createdNamespace) {
                runCatching { client.namespaces().withName(namespace).delete() }
                    .onFailure { logger.warn("Failed to delete namespace '{}'", namespace, it) }
            } else {
                listOf(nodePortTemplate, loadBalancerTemplate, jobTemplate, multiContainerTemplate).forEach { name ->
                    runCatching { client.resources(PodTemplate::class.java).inNamespace(namespace).withName(name).delete() }
                        .onFailure { logger.warn("Failed to delete PodTemplate '{}'", name, it) }
                }
            }
            altNamespaceCreated?.let { alt ->
                runCatching { client.namespaces().withName(alt).delete() }
                    .onFailure { logger.warn("Failed to delete alt namespace '{}'", alt, it) }
            }
        }

        if (::executor.isInitialized) executor.shutdownNow()
        if (::client.isInitialized) client.close()
    }

    @Test
    fun discoversAllProfiles() {
        val ids = service.getAvailableProfiles().map { it.id }.toSet()
        assertTrue(ids.contains("$namespace:$nodePortTemplate"), "NodePort profile not discovered; found: $ids")
        assertTrue(ids.contains("$namespace:$loadBalancerTemplate"), "LoadBalancer profile not discovered; found: $ids")
        assertTrue(ids.contains("$namespace:$jobTemplate"), "Job profile not discovered; found: $ids")
    }

    /**
     * NAMESPACE_DISCOVERY=`any`: profiles and executions span every namespace in the cluster —
     * the mode a multi-tenant deployment uses when per-tenant workloads live in per-tenant
     * namespaces. Exercises a second namespace this test creates and owns, and a second service
     * instance constructed with `namespaceDiscovery = "any"` sharing the same client/executor:
     * profiles from both namespaces list (each id namespaced, each profile carrying its own
     * namespace), the discovery=`configured` service still sees only its own, and an execution
     * launched from the foreign-namespace profile lists back with that namespace.
     */
    @Test
    fun anyNamespaceDiscoveryListsAndExecutesAcrossNamespaces() {
        val altNamespace = "$namespace-alt-$runSuffix".also { altNamespaceCreated = it }
        if (client.namespaces().withName(altNamespace).get() == null) {
            client.namespaces()
                .resource(NamespaceBuilder().withNewMetadata().withName(altNamespace).endMetadata().build())
                .create()
        }

        val altTemplate = "conductor-it-alt-job-$runSuffix"
        createJobTemplate(altTemplate, altNamespace)

        val anyDiscovery = KubernetesOrchestrationService(
            namespace = namespace,
            namespaceDiscovery = "any",
            jobSet = jobSet,
            jobSetName = jobSet,
            jobSetDescription = "",
            pollInterval = "3000",
            watchEnabled = "false",
            kubeconfigPath = System.getenv("KUBERNETES_IT_KUBECONFIG") ?: "",
            client = client,
            executor = executor
        )

        // Profiles: every namespace's templates are visible under discovery=any, each id namespaced.
        val ids = anyDiscovery.getAvailableProfiles().map { it.id }.toSet()
        assertTrue(ids.contains("$namespace:$jobTemplate"), "Configured-namespace profile missing from discovery=any listing: $ids")
        assertTrue(ids.contains("$altNamespace:$altTemplate"), "Second-namespace profile missing from discovery=any listing: $ids")

        // The discovery=configured service still sees only its own namespace's profiles.
        val configuredIds = service.getAvailableProfiles().map { it.id }.toSet()
        assertFalse(configuredIds.contains("$altNamespace:$altTemplate"), "discovery=configured leaked a foreign-namespace profile: $configuredIds")

        // findAvailableProfile resolves across namespaces, and execute() lands in the profile's own namespace.
        val profile = anyDiscovery.findAvailableProfile("$altNamespace:$altTemplate")
            ?: throw AssertionError("Profile '$altNamespace:$altTemplate' not found under discovery=any")
        assertEquals(profile.namespace, altNamespace, "Expected the profile to carry its own namespace")

        val execution = anyDiscovery.execute(JobRequest(profile = profile)).also { executions += it }
        anyDiscovery.getFutureForStatus(execution, JobStatus.COMPLETED).get(timeoutMinutes, TimeUnit.MINUTES)

        // listExecutions() under discovery=any reports the workload with its own namespace.
        val listed = anyDiscovery.listExecutions().firstOrNull { it.id == execution.id }
            ?: throw AssertionError("Execution '${execution.id}' missing from discovery=any listing")
        assertEquals(listed.namespace, altNamespace, "Expected the execution to report its own namespace")
    }

    @Test
    fun nodePortServiceServesHttp() = runServerProfile(nodePortTemplate)

    @Test
    fun loadBalancerServiceServesHttp() = runServerProfile(loadBalancerTemplate)

    @Test
    fun serviceEnvVarsInjectedWhenPortsExposed() {
        val profile = service.findAvailableProfile("$namespace:$nodePortTemplate")
            ?: throw AssertionError("Profile '$namespace:$nodePortTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)

        val (ns, _, podName) = decodeExecutionId(execution.id)
        val pod = client.pods().inNamespace(ns).withName(podName).get()
            ?: throw AssertionError("Pod '$podName' not found")
        val env = pod.spec?.containers.orEmpty().first().env.orEmpty().associate { it.name to it.value }

        assertEquals(env[KubernetesOrchestrationService.SERVICE_NAME_ENV_VAR], podName)
        assertEquals(env[KubernetesOrchestrationService.SERVICE_PORTS_ENV_VAR], "$podPort/$podProtocol")
    }

    @Test
    fun serviceEnvVarsAbsentWhenNoPortsExposed() {
        val profile = service.findAvailableProfile("$namespace:$jobTemplate")
            ?: throw AssertionError("Profile '$namespace:$jobTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.COMPLETED).get(timeoutMinutes, TimeUnit.MINUTES)

        val (ns, _, jobName) = decodeExecutionId(execution.id)
        val pod = client.pods().inNamespace(ns).withLabel(LABEL_OWNED_BY, jobName).list().items.firstOrNull()
            ?: throw AssertionError("No Pod found owned by Job '$jobName'")
        val env = pod.spec?.containers.orEmpty().first().env.orEmpty().map { it.name }

        assertFalse(env.contains(KubernetesOrchestrationService.SERVICE_NAME_ENV_VAR))
        assertFalse(env.contains(KubernetesOrchestrationService.SERVICE_PORTS_ENV_VAR))
    }

    @Test
    fun oneOffJobReachesCompletion() {
        val profile = service.findAvailableProfile("$namespace:$jobTemplate")
            ?: throw AssertionError("Job profile '$namespace:$jobTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }

        val completed = service.getFutureForStatus(execution, JobStatus.COMPLETED)
            .get(timeoutMinutes, TimeUnit.MINUTES)
        assertEquals(completed.status, JobStatus.COMPLETED, "Job did not reach COMPLETED")
    }

    @Test
    fun streamStdioThrowsForCompletedJob() {
        val profile = service.findAvailableProfile("$namespace:$jobTemplate")
            ?: throw AssertionError("Job profile '$namespace:$jobTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.COMPLETED).get(timeoutMinutes, TimeUnit.MINUTES)

        assertThrows(StdioUnavailableException::class.java) { service.streamStdio(execution, null) }
    }

    @Test
    fun streamStdioAttachesToRunningPod() {
        val profile = service.findAvailableProfile("$namespace:$nodePortTemplate")
            ?: throw AssertionError("Profile '$namespace:$nodePortTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)

        service.streamStdio(execution, null).use { stdio ->
            assertNotNull(stdio.stdin, "Expected a stdin stream")
            assertNotNull(stdio.stdout, "Expected a stdout stream")
            assertNotNull(stdio.stderr, "Expected a stderr stream")
        }
    }

    private fun runServerProfile(templateName: String) {
        val profile = service.findAvailableProfile("$namespace:$templateName")
            ?: throw AssertionError("Profile '$namespace:$templateName' not found")

        val environment = mapOf("TEST_A" to "a", "TEST_B" to "b")
        val execution = service.execute(JobRequest(profile = profile, environment = environment)).also { executions += it }

        val running = service.getFutureForStatus(execution, JobStatus.RUNNING)
            .get(timeoutMinutes, TimeUnit.MINUTES)
        assertEquals(running.status, JobStatus.RUNNING, "Workload '$templateName' did not reach RUNNING")

        val resolved = awaitEndpoints(execution)
        assertFalse(resolved.endpoints.isEmpty(), "Expected at least one endpoint for '$templateName' when RUNNING")

        if (httpCheck) {
            val endpoint = resolved.endpoints.first()
            val uri = URI.create("http://${endpoint.host}:${endpoint.port}$httpPath")
            logger.info("HTTP check ({}) against {}", templateName, uri)
            val response = httpGetWithRetry(uri)
            assertTrue(response.statusCode() in 200..499, "Unexpected HTTP status ${response.statusCode()} from $uri")
        }
    }

    /**
     * Retries the HTTP GET a few times with a short backoff. Reaching RUNNING only reflects the Pod's
     * phase; kube-proxy programs the NodePort/Service route via a separate, asynchronous reconciliation
     * loop with no ordering guarantee relative to phase reporting, so the route can still be a moment
     * behind — especially when RUNNING resolves quickly under [KubernetesAttributes.WATCH_ENABLED].
     */
    private fun httpGetWithRetry(
        uri: URI,
        attempts: Int = 5,
        initialBackoff: Duration = Duration.ofMillis(500)
    ): HttpResponse<Void> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(20)).build()

        var backoff = initialBackoff
        repeat(attempts - 1) { attempt ->
            try {
                return client.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (e: IOException) {
                logger.warn(
                    "HTTP check against {} failed (attempt {}/{}): {} — retrying in {}",
                    uri, attempt + 1, attempts, e.message, backoff
                )
                Thread.sleep(backoff.toMillis())
                backoff = backoff.multipliedBy(2)
            }
        }
        return client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    /**
     * Re-polls RUNNING status until endpoints populate or the timeout elapses. Needed for
     * `LoadBalancer` Services, whose external address is assigned shortly after the pod is running
     * (by `minikube tunnel`).
     */
    private fun awaitEndpoints(execution: JobExecution): JobExecution {
        val deadline = System.nanoTime() + Duration.ofMinutes(timeoutMinutes).toNanos()
        var latest = execution
        while (System.nanoTime() < deadline) {
            latest = service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)
            if (latest.endpoints.isNotEmpty()) return latest
            Thread.sleep(5_000)
        }
        return latest
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
            createdNamespace = true
            logger.info("Created namespace '{}'", namespace)
        }
    }

    private fun createServerTemplate(name: String, serviceType: String) {
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
                .addToAnnotations(ANN_EXPOSE_PORTS, "$podPort/$podProtocol")
                .addToAnnotations(ANN_SERVICE_TYPE, serviceType)
            .endMetadata()
            .withNewTemplate()
                .withNewSpec()
                    .withContainers(container)
                .endSpec()
            .endTemplate()
            .build()
        client.resources(PodTemplate::class.java).inNamespace(namespace).resource(template).create()
    }

    private fun createJobTemplate(name: String, ns: String = namespace) {
        val template = PodTemplateBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(ns)
                .addToLabels(LABEL_JOB_SET, jobSet)
                .addToAnnotations(ANN_WORKLOAD_KIND, "job")
            .endMetadata()
            .withNewTemplate()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .addNewContainer()
                        .withName("worker")
                        .withImage(jobImage)
                        .withCommand(jobCommand)
                    .endContainer()
                .endSpec()
            .endTemplate()
            .build()
        client.resources(PodTemplate::class.java).inNamespace(ns).resource(template).create()
    }

    private fun createMultiContainerTemplate(name: String) {
        fun loopContainer(containerName: String) = ContainerBuilder()
            .withName(containerName)
            .withImage(jobImage)
            .withCommand("sh", "-c", "while true; do echo hello-from-$containerName; sleep 1; done")
            .build()

        val template = PodTemplateBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(LABEL_JOB_SET, jobSet)
            .endMetadata()
            .withNewTemplate()
                .withNewSpec()
                    .withContainers(loopContainer(MULTI_CONTAINER_PRIMARY), loopContainer(MULTI_CONTAINER_SECONDARY))
                .endSpec()
            .endTemplate()
            .build()
        client.resources(PodTemplate::class.java).inNamespace(namespace).resource(template).create()
    }

    @Test
    fun discoversContainersForMultiContainerProfile() {
        val profile = service.findAvailableProfile("$namespace:$multiContainerTemplate")
            ?: throw AssertionError("Profile '$namespace:$multiContainerTemplate' not found")

        assertEquals(profile.containers.map { it.id }, listOf(MULTI_CONTAINER_PRIMARY, MULTI_CONTAINER_SECONDARY))
        assertTrue(profile.containers.first { it.id == MULTI_CONTAINER_PRIMARY }.primary, "Expected the first container to be primary")
        assertFalse(profile.containers.first { it.id == MULTI_CONTAINER_SECONDARY }.primary, "Expected the second container to not be primary")
    }

    @Test
    fun ttyExecuteSetsPtyOnPrimaryContainer() {
        val profile = service.findAvailableProfile("$namespace:$multiContainerTemplate")
            ?: throw AssertionError("Profile '$namespace:$multiContainerTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile, tty = true)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)

        val (ns, _, podName) = decodeExecutionId(execution.id)
        val pod = client.pods().inNamespace(ns).withName(podName).get()
            ?: throw AssertionError("Pod '$podName' not found")
        val primary = pod.spec?.containers.orEmpty().first { it.name == MULTI_CONTAINER_PRIMARY }
        assertTrue(primary.tty == true, "Expected tty=true on the primary container")
        assertTrue(primary.stdin == true, "Expected stdin=true on the primary container")
    }

    @Test
    fun streamStdioAttachesToRequestedContainer() {
        val profile = service.findAvailableProfile("$namespace:$multiContainerTemplate")
            ?: throw AssertionError("Profile '$namespace:$multiContainerTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)

        // streamStdio() is exec-like (kubectl exec, not kubectl attach): with no explicit command it
        // execs a fresh /bin/sh rather than attaching to the container's own long-running process, so
        // the expected greeting has to be requested via an explicit command instead of relying on the
        // container's background `while true; do echo ...; sleep 1; done` loop to have just emitted it.
        val echoCommand = listOf("echo", "hello-from-$MULTI_CONTAINER_SECONDARY")
        service.streamStdio(execution, MULTI_CONTAINER_SECONDARY, echoCommand).use { stdio ->
            val line = stdio.stdout.bufferedReader().readLine()
            assertEquals(line, "hello-from-$MULTI_CONTAINER_SECONDARY")
        }

        service.streamStdio(execution, MULTI_CONTAINER_PRIMARY, listOf("echo", "hello-from-$MULTI_CONTAINER_PRIMARY")).use { stdio ->
            val line = stdio.stdout.bufferedReader().readLine()
            assertEquals(line, "hello-from-$MULTI_CONTAINER_PRIMARY")
        }

        assertThrows(StdioUnavailableException::class.java) { service.streamStdio(execution, "no-such-container") }
    }

    private fun decodeExecutionId(id: String): Triple<String, String, String> {
        val parts = id.split(":")
        return Triple(parts[0], parts[1], parts[2])
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

}