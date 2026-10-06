package dev.getelements.conductor.kubernetes.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.exception.ReservedMetadataKeyException
import dev.getelements.conductor.exception.StdioUnavailableException
import dev.getelements.conductor.exception.UnknownContainerException
import dev.getelements.conductor.kubernetes.KubernetesExecutionDetails
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_EXPOSE_PORTS
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_SERVICE_TYPE
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.ANN_WORKLOAD_KIND
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_JOB_SET
import dev.getelements.conductor.kubernetes.service.KubernetesOrchestrationService.Companion.LABEL_OWNED_BY
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.PodTemplate
import io.fabric8.kubernetes.api.model.PodTemplateBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRule
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder
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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
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

        /** Non-`namazu.conductor` annotation keys, standing in for arbitrary infrastructure metadata. */
        private const val METADATA_OWNER_KEY = "docs.example.com/owner"
        private const val METADATA_NOTE_KEY = "run.example.com/note"

        /** A cosmetic reserved key — overridable per run, unlike the behavioural keys. */
        private const val METADATA_HIDDEN_KEY = "namazu.conductor/hidden"

        /**
         * The container-scoped form of [METADATA_HIDDEN_KEY] — a dot qualifier naming the container.
         * Pinned here to guard the dot form against prefix-exclusion regressions (e.g. anything that
         * would swallow it the way `default-container-exec.*` is excluded).
         */
        private const val METADATA_CONTAINER_HIDDEN_KEY = "namazu.conductor/hidden.sidecar"
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
    private val serviceGuardTemplate get() = "conductor-it-svcguard-$runSuffix"
    private lateinit var runSuffix: String

    private var podPort: Int = 80
    private var podProtocol: String = "tcp"
    private var httpCheck: Boolean = true
    private var httpPath: String = "/"
    private var timeoutMinutes: Long = 5

    private val executions = mutableListOf<JobExecution>()

    /** Names of the restricted ServiceAccounts (plus their Role/RoleBinding/Secret companions)
     *  created by the issue-#77 tests, all deleted in teardown. */
    private val restrictedIdentities = mutableListOf<String>()

    /** Kubernetes clients authenticating as [restrictedIdentities], closed in teardown. */
    private val restrictedClients = mutableListOf<KubernetesClient>()

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
        createJobWithServiceTemplate(serviceGuardTemplate)

        logger.info(
            "Created PodTemplates in namespace '{}': {}, {}, {}, {}, {}",
            namespace, nodePortTemplate, loadBalancerTemplate, jobTemplate, multiContainerTemplate, serviceGuardTemplate
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
            // Never delete the namespace itself here, even if this class created it: the namespace is
            // shared with KubernetesDaemonOrchestrationServiceIT (same default KUBERNETES_IT_NAMESPACE),
            // which may run in the same failsafe suite after this class, and deletion is async — a
            // sibling setUp racing the terminating namespace gets spurious "NamespaceTerminating" 403s.
            // Deleting only the templates this class created is sufficient; the namespace itself is
            // harmless to leave behind on an ephemeral CI cluster.
            listOf(nodePortTemplate, loadBalancerTemplate, jobTemplate, multiContainerTemplate, serviceGuardTemplate).forEach { name ->
                runCatching { client.resources(PodTemplate::class.java).inNamespace(namespace).withName(name).delete() }
                    .onFailure { logger.warn("Failed to delete PodTemplate '{}'", name, it) }
            }
            restrictedIdentities.forEach { name ->
                runCatching { client.services().inNamespace(namespace).withName(name).delete() }
                runCatching { client.secrets().inNamespace(namespace).withName("$name-token").delete() }
                runCatching { client.rbac().roleBindings().inNamespace(namespace).withName("$name-binding").delete() }
                runCatching { client.rbac().roles().inNamespace(namespace).withName("$name-role").delete() }
                runCatching { client.serviceAccounts().inNamespace(namespace).withName(name).delete() }
            }
            altNamespaceCreated?.let { alt ->
                runCatching { client.namespaces().withName(alt).delete() }
                    .onFailure { logger.warn("Failed to delete alt namespace '{}'", alt, it) }
            }
        }

        if (::executor.isInitialized) executor.shutdownNow()
        restrictedClients.forEach { runCatching { it.close() } }
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

    /**
     * Metadata is declared verbatim, overridden by the caller, and read back off the live workload.
     *
     * Three things worth pinning down, because each is a decision rather than an implementation
     * detail: a profile reports the *whole* annotation map including the `namazu.conductor` keys
     * Conductor also surfaces as typed fields; a caller's overrides merge over the declared set
     * rather than replacing it; and the reported set is read off the cluster rather than echoed
     * from the request, so it reflects what actually landed.
     */
    @Test
    fun metadataIsDeclaredVerbatimMergedWithOverridesAndReadBack() {
        val template = "conductor-it-metadata-$runSuffix"
        createMetadataTemplate(template)

        val profile = service.findAvailableProfile("$namespace:$template")
            ?: throw AssertionError("Profile '$namespace:$template' not found")

        // Declared verbatim: a plain infrastructure annotation, and a namazu.conductor key that
        // Conductor also surfaces as a typed field -- reported here too, under its full key.
        assertEquals(
            profile.metadata[METADATA_OWNER_KEY], "platform-team",
            "Expected a non-Conductor annotation to be reported verbatim in the profile's metadata"
        )
        assertEquals(
            profile.metadata[ANN_WORKLOAD_KIND], "job",
            "Expected the namazu.conductor/workload-kind key to be reported verbatim, not stripped"
        )

        // A behavioural reserved key (workload-kind picks what Conductor creates) is rejected, and
        // rejected before anything is created -- a caller error must not leave a half-dispatched
        // workload behind. Cosmetic reserved keys, by contrast, are free to override.
        val jobsBefore = client.batch().v1().jobs().inNamespace(namespace).list().items.size
        assertThrows(ReservedMetadataKeyException::class.java) {
            service.execute(JobRequest(profile = profile, metadata = mapOf(ANN_WORKLOAD_KIND to "pod")))
        }
        assertEquals(
            client.batch().v1().jobs().inNamespace(namespace).list().items.size, jobsBefore,
            "A rejected metadata override must not have created a Job"
        )

        val execution = service.execute(JobRequest(
            profile = profile,
            metadata = mapOf(
                METADATA_OWNER_KEY to "sre-oncall", METADATA_NOTE_KEY to "second attempt",
                METADATA_HIDDEN_KEY to "true", METADATA_CONTAINER_HIDDEN_KEY to "true"
            )
        )).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.COMPLETED).get(timeoutMinutes, TimeUnit.MINUTES)

        // An override wins over the declared value, a key the profile never declared is added, and
        // a key the caller said nothing about is left exactly as declared.
        assertEquals(execution.metadata[METADATA_OWNER_KEY], "sre-oncall", "Override did not win")
        assertEquals(execution.metadata[METADATA_NOTE_KEY], "second attempt", "New key was not added")
        assertEquals(execution.metadata[ANN_WORKLOAD_KIND], "job", "Unmentioned declared key was not preserved")

        // A cosmetic reserved key override is accepted, lands on the workload, and reads back --
        // including the container-scoped dot-qualified form.
        assertEquals(execution.metadata[METADATA_HIDDEN_KEY], "true", "Cosmetic reserved override was not accepted")
        assertEquals(execution.metadata[METADATA_CONTAINER_HIDDEN_KEY], "true", "Container-scoped cosmetic override was not accepted")

        // Actually on the cluster, not just in the returned object.
        val (_, _, jobName) = decodeExecutionId(execution.id)
        val onCluster = client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get()
            ?.spec?.template?.metadata?.annotations.orEmpty()
        assertEquals(onCluster[METADATA_OWNER_KEY], "sre-oncall", "Override did not land on the Job's pod template")
        assertEquals(onCluster[METADATA_NOTE_KEY], "second attempt", "New key did not land on the Job's pod template")
        assertEquals(onCluster[METADATA_HIDDEN_KEY], "true", "Cosmetic reserved override did not land on the Job's pod template")
        assertEquals(onCluster[METADATA_CONTAINER_HIDDEN_KEY], "true", "Container-scoped cosmetic override did not land on the Job's pod template")

        // And still reported after a round-trip through listExecutions().
        val listed = service.listExecutions().firstOrNull { it.id == execution.id }
            ?: throw AssertionError("Execution '${execution.id}' missing from listing")
        assertEquals(listed.metadata[METADATA_OWNER_KEY], "sre-oncall", "listExecutions() lost the override")
        assertEquals(listed.metadata[METADATA_NOTE_KEY], "second attempt", "listExecutions() lost the new key")
    }

    /**
     * Runtime annotations added to a Job's **top level** — where callers annotate at runtime (e.g.
     * the agent's `kubectl annotate job/…` link publication) — surface in the read-back, merged
     * over the inner `spec.template` block, while a non-`namazu.conductor` top-level annotation
     * stays out of the reported metadata (see #67). The declared set from the template block is
     * preserved underneath the top-level overlay.
     */
    @Test
    fun jobWorkloadTopLevelAnnotationsSurfaceInReadBack() {
        val profile = service.findAvailableProfile("$namespace:$jobTemplate")
            ?: throw AssertionError("Job profile '$namespace:$jobTemplate' not found")

        val execution = service.execute(JobRequest(profile = profile, metadata = mapOf(
            METADATA_OWNER_KEY to "platform-team", METADATA_HIDDEN_KEY to "true"
        ))).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.COMPLETED).get(timeoutMinutes, TimeUnit.MINUTES)

        val (_, _, jobName) = decodeExecutionId(execution.id)
        // Note the keys must be valid Kubernetes annotation names — the ':' tag-separator form is
        // an ECS-only spelling that can't exist as a k8s annotation at all.
        annotateJobTopLevel(jobName, mapOf(
            "namazu.conductor/link.QuantumREST" to "https://example.internal/quantum/api/",
            "namazu.conductor/hidden.sidecar" to "true",
            METADATA_NOTE_KEY to "annotated at runtime"
        ))

        val listed = service.listExecutions().firstOrNull { it.id == execution.id }
            ?: throw AssertionError("Execution '${execution.id}' missing from listing")

        assertEquals(
            listed.metadata["namazu.conductor/link.QuantumREST"], "https://example.internal/quantum/api/",
            "A runtime link published on the Job's top level was not surfaced in the read-back"
        )
        assertEquals(
            listed.metadata[METADATA_CONTAINER_HIDDEN_KEY], "true",
            "A runtime top-level container-scoped cosmetic key was not surfaced"
        )
        assertEquals(listed.metadata[METADATA_HIDDEN_KEY], "true", "The declared set from the template block was lost")
        assertNull(
            listed.metadata[METADATA_NOTE_KEY],
            "A non-namazu.conductor top-level annotation must not leak into the reported metadata"
        )
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

    private fun buildClient(): KubernetesClient = KubernetesClientBuilder().withConfig(buildBaseConfig()).build()

    /** The shared kubeconfig-derived base [Config] (env-overridden), before per-identity tweaks. */
    private fun buildBaseConfig(): Config {
        val context = System.getenv("KUBERNETES_IT_CONTEXT")
        val kubeconfigPath = System.getenv("KUBERNETES_IT_KUBECONFIG")

        val config: Config = if (!kubeconfigPath.isNullOrBlank()) {
            Config.fromKubeconfig(context, File(kubeconfigPath).readText(), kubeconfigPath)
        } else {
            Config.autoConfigure(context)
        }

        System.getenv("KUBERNETES_IT_MASTER_URL")?.takeIf { it.isNotBlank() }?.let { config.masterUrl = it }
        return config
    }

    /**
     * Creates a ServiceAccount + Role + RoleBinding in the test namespace granting exactly
     * [rules], mints a long-lived token Secret for it, waits for RBAC propagation via a
     * SelfSubjectAccessReview against [awaitVerb]/[awaitResource], and returns a
     * [KubernetesOrchestrationService] whose Kubernetes client authenticates as that restricted
     * identity. Mirrors the production control-plane SA whose ClusterRole granted `services`
     * read-only — the shape that made bare Service deletes 403 on every stop (issue #77).
     */
    private fun restrictedConductorService(
        name: String,
        rules: List<PolicyRule>,
        awaitVerb: String,
        awaitResource: String
    ): KubernetesOrchestrationService {
        client.serviceAccounts().inNamespace(namespace).resource(
            ServiceAccountBuilder().withNewMetadata().withName(name).endMetadata().build()
        ).create()

        // Long-lived (Secret-based) SA token: the token controller fills data["token"] shortly
        // after the annotated Secret is created. Unlike TokenRequest tokens it never expires
        // mid-suite.
        val secretName = "$name-token"
        client.secrets().inNamespace(namespace).resource(
            SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .addToAnnotations("kubernetes.io/service-account.name", name)
                .endMetadata()
                .withType("kubernetes.io/service-account-token")
                .build()
        ).create()

        val token = (1..30).firstNotNullOfOrNull { attempt ->
            if (attempt > 1) Thread.sleep(1000)
            client.secrets().inNamespace(namespace).withName(secretName).get()?.data?.get("token")
                ?.let { String(Base64.getDecoder().decode(it)) }
        } ?: throw IllegalStateException("ServiceAccount token Secret '$secretName' was never populated")

        client.rbac().roles().inNamespace(namespace).resource(
            RoleBuilder()
                .withNewMetadata().withName("$name-role").endMetadata()
                .withRules(rules)
                .build()
        ).create()

        client.rbac().roleBindings().inNamespace(namespace).resource(
            RoleBindingBuilder()
                .withNewMetadata().withName("$name-binding").endMetadata()
                .withRoleRef(
                    RoleRefBuilder()
                        .withApiGroup("rbac.authorization.k8s.io").withKind("Role").withName("$name-role").build()
                )
                .withSubjects(
                    SubjectBuilder().withKind("ServiceAccount").withName(name).withNamespace(namespace).build()
                )
                .build()
        ).create()

        restrictedIdentities += name

        // The RBAC authorizer reads an informer cache: poll a SelfSubjectAccessReview (performed
        // as the restricted identity itself) until the binding's grants are visible, so the tests
        // never race the propagation.
        KubernetesClientBuilder().withConfig(
            buildBaseConfig().apply {
                oauthToken = token
                username = null
                password = null
                clientCertData = null
                clientKeyData = null
                namespace = namespace
            }
        ).build().use { reviewClient ->
            (1..30).firstOrNull { attempt ->
                if (attempt > 1) Thread.sleep(1000)
                val allowed = runCatching {
                    reviewClient.authorization().v1().selfSubjectAccessReview().create(
                        SelfSubjectAccessReviewBuilder()
                            .withNewSpec()
                                .withNewResourceAttributes()
                                    .withNamespace(namespace)
                                    .withVerb(awaitVerb)
                                    .withResource(awaitResource)
                                .endResourceAttributes()
                            .endSpec()
                            .build()
                    ).status?.allowed == true
                }.getOrNull() ?: false
                allowed
            } ?: throw IllegalStateException(
                "RBAC grants for '$name' ($awaitVerb on $awaitResource) never became visible to the authorizer"
            )
        }

        val restrictedClient = KubernetesClientBuilder().withConfig(
            buildBaseConfig().apply {
                oauthToken = token
                username = null
                password = null
                clientCertData = null
                clientKeyData = null
                namespace = namespace
            }
        ).build().also { restrictedClients += it }

        return KubernetesOrchestrationService(
            namespace = namespace,
            jobSet = jobSet,
            jobSetName = jobSet,
            jobSetDescription = "",
            pollInterval = "3000",
            watchEnabled = "false",
            kubeconfigPath = "",
            client = restrictedClient,
            executor = executor
        )
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

    /** A one-off job template that also declares `expose-ports` — the shape whose Service-create
     *  and Service-delete paths the restricted-identity tests (issue #77) exercise. */
    private fun createJobWithServiceTemplate(name: String) {
        val template = PodTemplateBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(LABEL_JOB_SET, jobSet)
                .addToAnnotations(ANN_WORKLOAD_KIND, "job")
                .addToAnnotations(ANN_EXPOSE_PORTS, "$podPort/$podProtocol")
                .addToAnnotations(ANN_SERVICE_TYPE, "ClusterIP")
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

    /** A one-off job template carrying arbitrary non-Conductor annotations, for the metadata test. */
    private fun createMetadataTemplate(name: String) {
        val template = PodTemplateBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels(LABEL_JOB_SET, jobSet)
                .addToAnnotations(ANN_WORKLOAD_KIND, "job")
                .addToAnnotations(METADATA_OWNER_KEY, "platform-team")
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

    /**
     * Per-container env (issue #73): `containerEnvironment` entries land on the containers they
     * name, the flat `environment` keeps targeting the primary, and a per-container entry wins over
     * a flat key on the same container. Asserted by exec-echoing the variables in each container.
     */
    @Test
    fun containerEnvironmentReachesNamedContainers() {
        val profile = service.findAvailableProfile("$namespace:$multiContainerTemplate")
            ?: throw AssertionError("Profile '$namespace:$multiContainerTemplate' not found")

        val execution = service.execute(JobRequest(
            profile = profile,
            environment = mapOf("CONDUCTOR_IT_FLAT" to "flat"),
            containerEnvironment = mapOf(
                MULTI_CONTAINER_PRIMARY to mapOf("CONDUCTOR_IT_FLAT" to "qualified-wins"),
                MULTI_CONTAINER_SECONDARY to mapOf("CONDUCTOR_IT_SIDE" to "side-val"),
            ),
        )).also { executions += it }
        service.getFutureForStatus(execution, JobStatus.RUNNING).get(timeoutMinutes, TimeUnit.MINUTES)

        fun envOf(container: String, variable: String): String =
            service.streamStdio(execution, container, listOf("sh", "-c", "echo -n \$$variable")).use { stdio ->
                stdio.stdout.bufferedReader().readText()
            }

        assertEquals(
            envOf(MULTI_CONTAINER_SECONDARY, "CONDUCTOR_IT_SIDE"), "side-val",
            "The sidecar never received its containerEnvironment entry"
        )
        assertEquals(
            envOf(MULTI_CONTAINER_PRIMARY, "CONDUCTOR_IT_FLAT"), "qualified-wins",
            "A per-container entry must win over the flat environment key on the same container"
        )
    }

    /** An unknown container name fails the launch outright (issue #73) — never a silent drop. */
    @Test
    fun containerEnvironmentRejectsUnknownContainer() {
        val profile = service.findAvailableProfile("$namespace:$multiContainerTemplate")
            ?: throw AssertionError("Profile '$namespace:$multiContainerTemplate' not found")

        assertThrows(UnknownContainerException::class.java) {
            service.execute(JobRequest(
                profile = profile,
                containerEnvironment = mapOf("no-such-container" to mapOf("A" to "b")),
            ))
        }
    }

    /**
     * A Service-create failure at dispatch rolls the just-created workload back (issue #77): the
     * launch fails with the Service error and **no Job is left running behind it**. Simulated the
     * way it happened live in production (namazu-cloud-instance#57): a service account whose
     * ClusterRole granted `services` read-only — exactly the pre-fix control-plane shape — so the
     * Service create 403s after the Job create already succeeded.
     */
    @Test
    fun serviceCreateFailureRollsBackWorkload() {
        val restricted = restrictedConductorService(
            name = "conductor-it-svccreate-deny-$runSuffix",
            rules = listOf(
                PolicyRuleBuilder()
                    .withApiGroups("").withResources("pods", "podtemplates", "services", "endpoints")
                    .withVerbs("get", "list", "watch").build(),
                PolicyRuleBuilder()
                    .withApiGroups("batch").withResources("jobs")
                    .withVerbs("get", "list", "watch", "create", "delete").build(),
            ),
            awaitVerb = "create",
            awaitResource = "jobs"
        )
        val profile = restricted.findAvailableProfile("$namespace:$serviceGuardTemplate")
            ?: throw AssertionError("Profile '$namespace:$serviceGuardTemplate' not found")

        try {
            restricted.execute(JobRequest(profile = profile))
            throw AssertionError("execute() must fail when the Service create is forbidden")
        } catch (e: KubernetesClientException) {
            assertEquals(403, e.code)
            assertTrue(
                e.message?.contains("services") == true,
                "The failure must name the Service create: ${e.message}"
            )
        }

        val leftovers = client.batch().v1().jobs().inNamespace(namespace)
            .withLabel(LABEL_OWNED_BY).list().items
            .filter { it.metadata.name.startsWith("$serviceGuardTemplate-") }
        assertTrue(
            leftovers.isEmpty(),
            "A Service-create failure must not leave the workload behind: ${leftovers.map { it.metadata.name }}"
        )
    }

    /**
     * A Service-delete failure after a successful workload teardown must not fail the stop (issue
     * #77): the Job is gone and `stop()` returns normally even though the Service delete 403s —
     * the live production failure that made the admin panel report "Failed to stop job" for a
     * stop that had actually succeeded. Simulated with a service account allowed to CREATE
     * services but not to DELETE them. The stranded Service is GC'd with the Job (ownerReference)
     * and only asserted opportunistically: its collection is asynchronous, so its absence cannot
     * be asserted reliably.
     */
    @Test
    fun stopSucceedsWhenServiceDeleteIsForbidden() {
        val restricted = restrictedConductorService(
            name = "conductor-it-svcdelete-deny-$runSuffix",
            rules = listOf(
                PolicyRuleBuilder()
                    .withApiGroups("").withResources("pods", "podtemplates", "endpoints")
                    .withVerbs("get", "list", "watch").build(),
                PolicyRuleBuilder()
                    .withApiGroups("").withResources("services")
                    .withVerbs("get", "list", "watch", "create").build(),
                PolicyRuleBuilder()
                    .withApiGroups("batch").withResources("jobs")
                    .withVerbs("get", "list", "watch", "create", "delete").build(),
            ),
            awaitVerb = "create",
            awaitResource = "services"
        )
        val profile = restricted.findAvailableProfile("$namespace:$serviceGuardTemplate")
            ?: throw AssertionError("Profile '$namespace:$serviceGuardTemplate' not found")

        val execution = restricted.execute(JobRequest(profile = profile))
        val runName = decodeExecutionId(execution.id).third

        restricted.stop(execution)

        assertNull(
            client.batch().v1().jobs().inNamespace(namespace).withName(runName).get(),
            "The Job itself must be deleted"
        )
        client.services().inNamespace(namespace).withName(runName).get()?.let { stranded ->
            logger.info(
                "Service '{}' still present after the forbidden delete, as expected (it will be GC'd with the Job's ownerReference); labels: {}",
                runName, stranded.metadata.labels
            )
            client.services().inNamespace(namespace).withName(runName).delete()
        }
    }

    /**
     * Stopping a HELM execution must succeed with **no native classifier bundled** in the .elm
     * (issue #78): helm-java resolves the native library for the runtime OS/arch on first use via
     * its built-in RemoteJarLoader — downloading the matching classifier artifact from Maven
     * Central and caching it in java.io.tmpdir — instead of whatever classifier the build machine's
     * Maven profile happened to bake in (the arm64 ws tier previously 500'd every HELM stop with an
     * amd64-only bundle). Uninstalling a release that was never installed exercises the entire
     * path — native resolution, kube-config resolution, and helm-java's own ignoreNotFound
     * handling — while leaving no real release behind.
     */
    @Test
    fun helmStopOnMissingReleaseSucceeds() {
        val releaseName = "conductor-it-helm-missing-$runSuffix"
        val execution = JobExecution(
            id = "$namespace:helm:$releaseName",
            status = JobStatus.RUNNING,
            details = KubernetesExecutionDetails(
                namespace = namespace,
                workloadKind = "helm",
                name = releaseName
            ),
            namespace = namespace
        )

        service.stop(execution)
    }

    private fun decodeExecutionId(id: String): Triple<String, String, String> {
        val parts = id.split(":")
        return Triple(parts[0], parts[1], parts[2])
    }

    /**
     * Adds [annotations] to a Job's **top-level** `metadata.annotations`, retrying on 409 Conflict:
     * a Job that just reached COMPLETED is still mutated concurrently by the job controller
     * (status/managedFields bookkeeping), so a single replace can race it — each attempt re-fetches
     * the latest version, which settles the conflict.
     */
    private fun annotateJobTopLevel(jobName: String, annotations: Map<String, String>) {
        repeat(5) { attempt ->
            try {
                client.batch().v1().jobs().inNamespace(namespace).withName(jobName).edit { job ->
                    JobBuilder(job).editMetadata().apply {
                        annotations.forEach { (k, v) -> addToAnnotations(k, v) }
                    }.endMetadata().build()
                }
                return
            } catch (e: KubernetesClientException) {
                if (e.code != 409) throw e
                logger.info("PATCH conflict annotating job '{}' (attempt {}); retrying", jobName, attempt + 1)
                Thread.sleep(1000)
            }
        }
        throw AssertionError("Could not annotate job '$jobName' — 409 Conflict did not settle after retries")
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

}