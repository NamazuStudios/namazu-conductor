package dev.getelements.conductor.kubernetes.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.exception.StdioUnavailableException
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_EXPOSE_PORTS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_SERVICE_TYPE
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_WORKLOAD_KIND
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_JOB_SET
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
    private lateinit var runSuffix: String

    private var podPort: Int = 80
    private var podProtocol: String = "tcp"
    private var httpCheck: Boolean = true
    private var httpPath: String = "/"
    private var timeoutMinutes: Long = 5

    private var createdNamespace: Boolean = false
    private val executions = mutableListOf<JobExecution>()

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
            pollInterval = "3000",
            watchEnabled = env("KUBERNETES_IT_WATCH_ENABLED", "false"),
            client = client,
            executor = executor
        )

        ensureNamespace()
        createServerTemplate(nodePortTemplate, "NodePort")
        createServerTemplate(loadBalancerTemplate, "LoadBalancer")
        createJobTemplate(jobTemplate)

        logger.info("Created PodTemplates in namespace '{}': {}, {}, {}", namespace, nodePortTemplate, loadBalancerTemplate, jobTemplate)
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
                listOf(nodePortTemplate, loadBalancerTemplate, jobTemplate).forEach { name ->
                    runCatching { client.resources(PodTemplate::class.java).inNamespace(namespace).withName(name).delete() }
                        .onFailure { logger.warn("Failed to delete PodTemplate '{}'", name, it) }
                }
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

    @Test
    fun nodePortServiceServesHttp() = runServerProfile(nodePortTemplate)

    @Test
    fun loadBalancerServiceServesHttp() = runServerProfile(loadBalancerTemplate)

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

        assertThrows(StdioUnavailableException::class.java) { service.streamStdio(execution) }
    }

    @Test
    fun streamStdioAttachesToRunningPod() {
        val profile = service.findAvailableProfile("$namespace:$nodePortTemplate")
            ?: throw AssertionError("Profile '$namespace:$nodePortTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile)).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)

        service.streamStdio(execution).use { stdio ->
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

    private fun createJobTemplate(name: String) {
        val template = PodTemplateBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
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
        client.resources(PodTemplate::class.java).inNamespace(namespace).resource(template).create()
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

}