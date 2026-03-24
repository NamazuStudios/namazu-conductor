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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.AlreadyExistsException
import software.amazon.awssdk.services.cloudformation.model.Capability
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecs.EcsClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration test for [EcsOrchestrationService].
 *
 * Before the suite runs, deploys `integration-test.yaml` (bundled on the test classpath) as a
 * CloudFormation stack. The stack provisions the ECS cluster, task definition, VPC networking,
 * and a least-privilege IAM user whose credentials are used for the ECS and EC2 API calls.
 * After the suite the stack is destroyed.
 *
 * **Prerequisites — environment variables:**
 * - `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` — deployer credentials with permission to
 *   create and delete the CloudFormation stack (see `integration-test-deployer.yaml`).
 * - `AWS_REGION` — AWS region in which to deploy the stack.
 * - `CFN_STACK_NAME` — (optional) stack name; defaults to `conductor-integration-test`.
 *
 * The test is skipped automatically if `AWS_REGION` is absent. Run via:
 * ```
 * AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 mvn verify -pl ecs
 * ```
 */
class EcsOrchestrationServiceIT {

    private lateinit var stackName: String
    private lateinit var taskFamily: String
    private lateinit var cluster: String
    private lateinit var cfnClient: CloudFormationClient
    private lateinit var ecsClient: EcsClient
    private lateinit var executor: ExecutorService
    private lateinit var service: EcsOrchestrationService
    private lateinit var httpClient: Client

    private var executionId: String? = null

    @BeforeClass
    fun setUp() {

        val region = System.getenv("AWS_REGION")
            ?: throw SkipException("AWS_REGION environment variable is not set — skipping ECS integration tests")

        stackName = System.getenv("CFN_STACK_NAME") ?: "conductor-integration-test"

        val awsRegion = Region.of(region)
        cfnClient = CloudFormationClient.builder().region(awsRegion).build()

        val templateBody = javaClass.getResourceAsStream("/integration-test.yaml")
            ?.bufferedReader()?.readText()
            ?: error("integration-test.yaml not found on test classpath")

        deployStack(templateBody)

        val outputs = cfnClient.describeStacks { it.stackName(stackName) }
            .stacks().first().outputs()
            .associateBy { it.outputKey() }

        cluster     = outputs.getValue("EcsCluster").outputValue()
        taskFamily  = outputs.getValue("EcsTaskFamily").outputValue()
        val subnets         = outputs.getValue("EcsSubnets").outputValue()
        val securityGroups  = outputs.getValue("EcsSecurityGroups").outputValue()
        val keyId           = outputs.getValue("AwsAccessKeyId").outputValue()
        val secretKey       = outputs.getValue("AwsSecretAccessKey").outputValue()

        val testCredentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(keyId, secretKey)
        )

        ecsClient = EcsClient.builder().region(awsRegion).credentialsProvider(testCredentials).build()
        val ec2Client = Ec2Client.builder().region(awsRegion).credentialsProvider(testCredentials).build()
        executor  = Executors.newCachedThreadPool()
        httpClient = ClientBuilder.newClient()

        service = EcsOrchestrationService(
            cluster        = cluster,
            subnets        = subnets,
            securityGroups = securityGroups,
            ecsClient      = ecsClient,
            ec2Client      = ec2Client,
            executor       = executor
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
        if (::httpClient.isInitialized) httpClient.close()

        if (::cfnClient.isInitialized) {
            try {
                cfnClient.deleteStack { it.stackName(stackName) }
                cfnClient.waiter().waitUntilStackDeleteComplete { it.stackName(stackName) }
            } catch (e: Exception) {
                System.err.println("Warning: failed to delete stack '$stackName': ${e.message}")
            }
            cfnClient.close()
        }
    }

    @Test
    fun launchNginxAndVerifyHttp() {
        val profile = service.findAvailableProfile(taskFamily)
            ?: throw AssertionError("Profile '$taskFamily' not found — stack outputs may be stale")

        val execution = service.execute(JobRequest(profile = profile))
        executionId = execution.id

        val running = service
            .getFutureForStatus(execution, JobStatus.RUNNING)
            .get(10, TimeUnit.MINUTES)

        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint when RUNNING")

        val endpoint = running.endpoints.first()

        val response = httpClient
            .target("http://${endpoint.host}:${endpoint.port}/")
            .request()
            .get()

        assertEquals(response.status, 200, "Expected HTTP 200 from task on ${endpoint.host}:${endpoint.port}")
    }

    private fun deployStack(templateBody: String) {
        try {
            cfnClient.createStack {
                it.stackName(stackName)
                it.templateBody(templateBody)
                it.capabilities(Capability.CAPABILITY_NAMED_IAM)
            }
        } catch (e: AlreadyExistsException) {
            System.err.println("Stack '$stackName' already exists — using existing outputs")
            return
        }
        cfnClient.waiter().waitUntilStackCreateComplete { it.stackName(stackName) }
    }

}