package dev.getelements.conductor.edgegap.service

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import dev.getelements.conductor.IpPlacement
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import jakarta.ws.rs.client.*
import org.slf4j.LoggerFactory
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.fail
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration test for [EdgeGapOrchestrationService]. Requires a real EdgeGap account with an
 * application named `"integration_test"` containing a version named `"nginx"`.
 *
 * The test deploys the nginx container with a custom command that writes a "Hello World" page,
 * waits for the deployment to reach [JobStatus.RUNNING], then performs an HTTP GET against the
 * exposed endpoint to verify the page is served correctly.
 *
 * **Prerequisites:**
 * - Environment variable `EDGEGAP_API_KEY` must be set.
 * - The EdgeGap account must have an active app `"integration_test"` / version `"nginx"`.
 *
 * The test fails (does not skip) if `EDGEGAP_API_KEY` is absent — a missing/misconfigured
 * environment should surface as a failure, not a silently-green skip. Run via:
 * ```
 * EDGEGAP_API_KEY=<key> mvn verify -pl edgegap
 * ```
 */
class EdgeGapOrchestrationServiceIT {

    private val logger = LoggerFactory.getLogger(EdgeGapOrchestrationServiceIT::class.java)

    private lateinit var apiKey: String
    private lateinit var client: Client
    private lateinit var executor: ExecutorService
    private lateinit var service: EdgeGapOrchestrationService

    private var executionId: String? = null

    @BeforeClass
    fun setUp() {

        apiKey = System.getenv("EDGEGAP_API_KEY")
            ?: error("EDGEGAP_API_KEY environment variable is not set")

        client = ClientBuilder.newBuilder()
            .register(JacksonJsonProvider::class.java)
            .register(ClientResponseFilter { req: ClientRequestContext, ctx: ClientResponseContext ->
                val body = ctx.entityStream.bufferedReader().readText()
                logger.debug("EdgeGap {} {}: {}", ctx.status, req.uri, body)
                ctx.entityStream = body.byteInputStream()
            })
            .build()

        executor = Executors.newCachedThreadPool()

        service = EdgeGapOrchestrationService(
            apiKey = apiKey,
            baseUrl = "https://api.edgegap.com",
            pollingIntervalMs = 5000L,
            client = client,
            executor = executor,
        )

    }

    @AfterClass(alwaysRun = true)
    fun tearDown() {
        executionId?.let { stopDeployment(it) }
        if (::executor.isInitialized) executor.shutdownNow()
        if (::client.isInitialized) client.close()
    }

    @Test
    fun deployNginxAndVerifyHelloWorld() {
        val profile = service.findAvailableProfile("integration_test:nginx")
            ?: throw AssertionError("Profile 'integration_test:nginx' not found — ensure the app and version exist in the EdgeGap account")

        val publicIp = client.target("https://api.ipify.org")
            .request()
            .get(String::class.java)
            .trim()

        val environment = mapOf(
            "TEST_A" to "test_a",
            "TEST_B" to "test_b",
            "TEST_C" to "test_c",
            "TEST_D" to "test_d"
        )

        val execution = service.execute(
            JobRequest(
                profile = profile,
                placement = listOf(IpPlacement(ip = publicIp)),
                environment = environment
            )
        )

        executionId = execution.id

        val running = service
            .getFutureForStatus(execution, JobStatus.RUNNING)
            .get(10, TimeUnit.MINUTES)

        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint when RUNNING")

        val endpoint = running.endpoints.first()
        val target = client.target("http://${endpoint.host}:${endpoint.port}/test_context.json")

        // EdgeGap's edge ingress can take a few seconds to start routing traffic after a
        // deployment reaches RUNNING, so tolerate a brief propagation window here. Empirically
        // this 403 is intermittent rather than a predictable startup curve (4/5 manual runs
        // returned 200 immediately; the 1 failure stayed at 403 for the entire prior 30s window
        // with no sign of clearing) — widening the window is cheap insurance, not a confirmed fix.
        var response = target.request().get()
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)

        while (response.status != 200 && System.currentTimeMillis() < deadline) {
            logger.debug("Endpoint not ready yet (HTTP {}), retrying...", response.status)
            response.close()
            Thread.sleep(2000)
            response = target.request().get()
        }

        assertEquals(response.status, 200, "Expected HTTP 200 from nginx")

        val context = response.readEntity(TestContext::class.java)
        assertEquals(context.args, emptyList<String>(), "args mismatch")
        assertEquals(context.environment, environment, "environment mismatch")

    }

    /**
     * Stops the EdgeGap deployment via `DELETE /v1/stop/{requestId}`. Called in teardown
     * regardless of test outcome to avoid leaving orphaned deployments in the account.
     */
    private fun stopDeployment(requestId: String) {
        try {
            client.target("https://api.edgegap.com")
                .path("/v1/stop/{request_id}")
                .resolveTemplate("request_id", requestId)
                .request()
                .header("Authorization", apiKey)
                .delete()
        } catch (e: Exception) {
            logger.warn("Failed to stop EdgeGap deployment {}", requestId, e)
        }
    }

}

private data class TestContext @JsonCreator constructor(
    @JsonProperty("args") val args: List<String>,
    @JsonProperty("environment") val environment: Map<String, String>
)
