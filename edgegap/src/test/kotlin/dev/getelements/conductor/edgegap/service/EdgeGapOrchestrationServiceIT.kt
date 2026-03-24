package dev.getelements.conductor.edgegap.service

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import dev.getelements.conductor.IpPlacement
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientResponseFilter
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.SkipException
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
 * The test is skipped automatically if `EDGEGAP_API_KEY` is absent. Run via:
 * ```
 * EDGEGAP_API_KEY=<key> mvn verify -pl edgegap
 * ```
 */
class EdgeGapOrchestrationServiceIT {

    private lateinit var apiKey: String
    private lateinit var client: Client
    private lateinit var executor: ExecutorService
    private lateinit var service: EdgeGapOrchestrationService

    private var executionId: String? = null

    @BeforeClass
    fun setUp() {

        apiKey = (System.getenv("EDGEGAP_API_KEY")
            ?: throw SkipException("EDGEGAP_API_KEY environment variable is not set — skipping EdgeGap integration tests"))
            .removePrefix("token ")

        client = ClientBuilder.newBuilder()
            .register(JacksonJsonProvider::class.java)
            .register(ClientResponseFilter { req: ClientRequestContext, ctx: ClientResponseContext ->
                val body = ctx.entityStream.bufferedReader().readText()
                System.err.println("EdgeGap ${ctx.status} ${req.uri}: $body")
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

        val args = listOf(
            "a", "b", "c"
        )

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
//                args = args,
                environment = environment
            )
        )

        executionId = execution.id

        val running = service
            .getFutureForStatus(execution, JobStatus.RUNNING)
            .get(10, TimeUnit.MINUTES)

        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint when RUNNING")

        val endpoint = running.endpoints.first()

        val response = client.target("http://${endpoint.host}:${endpoint.port}/test_context.json")
            .request()
            .get()

        assertEquals(response.status, 200, "Expected HTTP 200 from nginx")

        val context = response.readEntity(TestContext::class.java)
        assertEquals(context.args, args, "args mismatch")
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
                .header("Authorization", "token $apiKey")
                .delete()
        } catch (e: Exception) {
            System.err.println("Warning: failed to stop EdgeGap deployment $requestId: ${e.message}")
        }
    }

}

private data class TestContext @JsonCreator constructor(
    @JsonProperty("args") val args: List<String>,
    @JsonProperty("environment") val environment: Map<String, String>
)
