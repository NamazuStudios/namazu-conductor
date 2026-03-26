package dev.getelements.conductor.ecs.service

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.slf4j.LoggerFactory
import org.testng.annotations.AfterClass
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.amazon.awssdk.services.cloudformation.model.AlreadyExistsException
import software.amazon.awssdk.services.cloudformation.model.Capability
import software.amazon.awssdk.services.cloudformation.model.Parameter
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
 * - `CFN_DEPLOYER_STACK_NAME` — (optional) name of the deployer stack to read the ECR registry URL
 *   from; defaults to `conductor-integration-test-deployer`.
 * - `CFN_IMAGE_NAME` — (optional) image name and tag within the ECR registry;
 *   defaults to `conductor-integration-test:latest`.
 *
 * The test is skipped automatically if `AWS_REGION` is absent. Run via:
 * ```
 * AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 mvn verify -pl ecs
 * ```
 */
class EcsOrchestrationServiceIT {

    private val logger = LoggerFactory.getLogger(EcsOrchestrationServiceIT::class.java)

    private lateinit var stackName: String
    private lateinit var taskFamily: String
    private lateinit var ec2TaskFamily: String
    private lateinit var cluster: String
    private lateinit var cfnClient: CloudFormationClient
    private lateinit var ecsClient: EcsClient
    private lateinit var executor: ExecutorService
    private lateinit var service: EcsOrchestrationService
    private lateinit var httpClient: Client

    private var fargateExecutionId: String? = null
    private var ec2ExecutionId: String? = null

    @BeforeClass
    fun setUp() {

        val region = System.getenv("AWS_REGION")
            ?: error("AWS_REGION environment variable is not set")

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

        cluster       = outputs.getValue("EcsCluster").outputValue()
        taskFamily    = outputs.getValue("EcsTaskFamily").outputValue()
        ec2TaskFamily = outputs.getValue("EcsEc2TaskFamily").outputValue()
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
        httpClient = ClientBuilder.newBuilder()
            .register(JacksonJsonProvider::class.java)
            .build()

        service = EcsOrchestrationService(
            cluster        = cluster,
            subnets        = subnets,
            securityGroups = securityGroups,
            jobSet         = "default",
            ecsClient      = ecsClient,
            ec2Client      = ec2Client,
            executor       = executor
        )
    }

    @AfterClass(alwaysRun = true)
    fun tearDown() {
        for ((label, id) in listOf("fargate" to fargateExecutionId, "ec2" to ec2ExecutionId)) {
            id?.let {
                try {
                    service.stop(JobExecution(id = it, status = JobStatus.RUNNING))
                } catch (e: Exception) {
                    logger.warn("Failed to stop {} ECS task {}", label, it, e)
                }
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
                logger.warn("Failed to delete stack '{}'", stackName, e)
            }
            cfnClient.close()
        }
    }

    @Test
    fun launchFargateAndVerifyTestContext() {
        val profile = service.findAvailableProfile(taskFamily)
            ?: throw AssertionError("Fargate profile '$taskFamily' not found")

        val environment = mapOf(
            "TEST_A" to "test_a",
            "TEST_B" to "test_b",
            "TEST_C" to "test_c",
            "TEST_D" to "test_d"
        )

        val execution = service.execute(JobRequest(profile = profile, environment = environment))
        fargateExecutionId = execution.id

        val running = service.getFutureForStatus(execution, JobStatus.RUNNING).get(10, TimeUnit.MINUTES)
        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint when RUNNING (Fargate)")

        val endpoint = running.endpoints.first()
        val response = httpClient
            .target("http://${endpoint.host}:${endpoint.port}/test_context.json")
            .request().get()

        assertEquals(response.status, 200, "Expected HTTP 200 from Fargate task on ${endpoint.host}:${endpoint.port}")
        val context = response.readEntity(TestContext::class.java)
        assertEquals(context.args, emptyList<String>(), "args mismatch (Fargate)")
        assertEquals(context.environment, environment, "environment mismatch (Fargate)")
    }

    @Test
    fun launchEc2SpotAndVerifyTestContext() {
        val profile = service.findAvailableProfile(ec2TaskFamily)
            ?: throw AssertionError("EC2 profile '$ec2TaskFamily' not found")

        val environment = mapOf(
            "TEST_A" to "test_a",
            "TEST_B" to "test_b",
            "TEST_C" to "test_c",
            "TEST_D" to "test_d"
        )

        val execution = service.execute(JobRequest(profile = profile, environment = environment))
        ec2ExecutionId = execution.id

        val running = service.getFutureForStatus(execution, JobStatus.RUNNING).get(10, TimeUnit.MINUTES)
        assertFalse(running.endpoints.isEmpty(), "Expected at least one endpoint when RUNNING (EC2)")

        val endpoint = running.endpoints.first()
        val response = httpClient
            .target("http://${endpoint.host}:${endpoint.port}/test_context.json")
            .request().get()

        assertEquals(response.status, 200, "Expected HTTP 200 from EC2 spot task on ${endpoint.host}:${endpoint.port}")
        val context = response.readEntity(TestContext::class.java)
        assertEquals(context.args, emptyList<String>(), "args mismatch (EC2)")
        assertEquals(context.environment, environment, "environment mismatch (EC2)")
    }

    private fun resolveRepositoryUrl(): String {
        val deployerStackName = System.getenv("CFN_DEPLOYER_STACK_NAME")
            ?: "conductor-integration-test-deployer"
        logger.info("Resolving ECR registry URL from deployer stack '{}'", deployerStackName)
        val ecrRepoUri = cfnClient.describeStacks { it.stackName(deployerStackName) }
            .stacks().first().outputs()
            .first { it.outputKey() == "EcrRepositoryUri" }
            .outputValue()
        // EcrRepositoryUri is the full repo URI, e.g.
        // 123456789012.dkr.ecr.us-east-1.amazonaws.com/conductor-integration-test
        // Strip the repo name to get the registry URL.
        return ecrRepoUri.substringBeforeLast("/")
    }

    private fun deployStack(templateBody: String) {
        val repositoryUrl = resolveRepositoryUrl()
        val imageName = System.getenv("CFN_IMAGE_NAME") ?: "conductor-integration-test:latest"
        logger.info("Deploying stack with image {}/{}", repositoryUrl, imageName)

        val params = listOf(
            Parameter.builder().parameterKey("RepositoryUrl").parameterValue(repositoryUrl).build(),
            Parameter.builder().parameterKey("ImageName").parameterValue(imageName).build(),
        )

        try {
            cfnClient.createStack {
                it.stackName(stackName)
                it.templateBody(templateBody)
                it.capabilities(Capability.CAPABILITY_NAMED_IAM)
                it.parameters(params)
            }
        } catch (e: AlreadyExistsException) {
            logger.info("Stack '{}' already exists - using existing outputs", stackName)
            return
        }
        cfnClient.waiter().waitUntilStackCreateComplete { it.stackName(stackName) }
    }

}

private data class TestContext @JsonCreator constructor(
    @JsonProperty("args") val args: List<String>,
    @JsonProperty("environment") val environment: Map<String, String>
)