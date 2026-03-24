package dev.getelements.conductor.ecs.service

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.SkipException
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecs.EcsClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration test for [EcsOrchestrationService]. Requires a real AWS account with an ECS task
 * definition family named `conductor-integration-test` (or overridden via `ECS_TASK_FAMILY`).
 *
 * The task definition must be:
 * - Fargate-compatible with `awsvpc` network mode
 * - Running an HTTP server on port 80 (e.g. nginx)
 * - Tagged with `conductor:launchType=FARGATE` and `conductor:assignPublicIp=ENABLED`
 * - Associated with a security group that allows inbound TCP on port 80
 *
 * **Prerequisites — environment variables:**
 * - `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` — AWS credentials (read by the SDK automatically)
 * - `AWS_REGION` — AWS region of the ECS cluster
 * - `ECS_CLUSTER` — short name or ARN of the ECS cluster
 * - `ECS_SUBNETS` — comma-separated VPC subnet IDs
 * - `ECS_SECURITY_GROUPS` — comma-separated security group IDs
 * - `ECS_TASK_FAMILY` — (optional) task definition family name; defaults to `conductor-integration-test`
 *
 * The test is skipped automatically if any required variable is absent. Run via:
 * ```
 * AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 \
 * ECS_CLUSTER=my-cluster ECS_SUBNETS=subnet-abc ECS_SECURITY_GROUPS=sg-xyz \
 * mvn verify -pl ecs
 * ```
 */
class EcsOrchestrationServiceIT {

    private lateinit var cluster: String
    private lateinit var ecsClient: EcsClient
    private lateinit var ec2Client: Ec2Client
    private lateinit var executor: ExecutorService
    private lateinit var service: EcsOrchestrationService
    private lateinit var httpClient: Client

    private var executionId: String? = null

    @BeforeClass
    fun setUp() {
        val region = System.getenv("AWS_REGION")
            ?: throw SkipException("AWS_REGION environment variable is not set — skipping ECS integration tests")
        cluster = System.getenv("ECS_CLUSTER")
            ?: throw SkipException("ECS_CLUSTER environment variable is not set — skipping ECS integration tests")
        val subnets = System.getenv("ECS_SUBNETS")
            ?: throw SkipException("ECS_SUBNETS environment variable is not set — skipping ECS integration tests")
        val securityGroups = System.getenv("ECS_SECURITY_GROUPS")
            ?: throw SkipException("ECS_SECURITY_GROUPS environment variable is not set — skipping ECS integration tests")

        val awsRegion = Region.of(region)
        ecsClient = EcsClient.builder().region(awsRegion).build()
        ec2Client = Ec2Client.builder().region(awsRegion).build()
        executor = Executors.newCachedThreadPool()
        httpClient = ClientBuilder.newClient()

        service = EcsOrchestrationService(
            cluster = cluster,
            subnets = subnets,
            securityGroups = securityGroups,
            ecsClient = ecsClient,
            executor = executor
        )
    }

    @AfterClass(alwaysRun = true)
    fun tearDown() {
        executionId?.let {
            try {
                service.stop(JobExecution(id = it, status = JobStatus.RUNNING))
            } catch (e: Exception) {
                System.err.println("Warning: failed to stop ECS task $it: ${e.message}")
            }
        }
        if (::executor.isInitialized) executor.shutdownNow()
        if (::ecsClient.isInitialized) ecsClient.close()
        if (::ec2Client.isInitialized) ec2Client.close()
        if (::httpClient.isInitialized) httpClient.close()
    }

    @Test
    fun launchNginxAndVerifyHttp() {
        val taskFamily = System.getenv("ECS_TASK_FAMILY") ?: "conductor-integration-test"

        val profile = service.findAvailableProfile(taskFamily)
            ?: throw AssertionError("Profile '$taskFamily' not found — ensure the task definition exists and is tagged with conductor:launchType")

        val execution = service.execute(JobRequest(profile = profile))
        executionId = execution.id

        val running = service
            .getFutureForStatus(execution, JobStatus.RUNNING)
            .get(10, TimeUnit.MINUTES)

        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint when RUNNING")

        val publicIp = fetchPublicIp(execution.id)
        val port = running.endpoints.first().port

        val response = httpClient
            .target("http://$publicIp:$port/")
            .request()
            .get()

        assertEquals(response.status, 200, "Expected HTTP 200 from task on $publicIp:$port")
    }

    private fun fetchPublicIp(taskArn: String): String {
        val task = ecsClient.describeTasks {
            it.cluster(cluster)
            it.tasks(taskArn)
        }.tasks().firstOrNull()
            ?: throw AssertionError("Task not found: $taskArn")

        val eniId = task.attachments()
            .firstOrNull { it.type() == "ElasticNetworkInterface" }
            ?.details()
            ?.firstOrNull { it.name() == "networkInterfaceId" }
            ?.value()
            ?: throw AssertionError("No ENI attachment found on task $taskArn")

        return ec2Client.describeNetworkInterfaces {
            it.networkInterfaceIds(eniId)
        }.networkInterfaces().firstOrNull()
            ?.association()
            ?.publicIp()
            ?: throw AssertionError("No public IP on ENI $eniId — ensure the task definition is tagged conductor:assignPublicIp=ENABLED")
    }

}