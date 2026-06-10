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
import software.amazon.awssdk.services.cloudformation.model.StackStatus
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.Filter
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
 */
class EcsOrchestrationServiceIT {

    private val logger = LoggerFactory.getLogger(EcsOrchestrationServiceIT::class.java)

    private lateinit var stackName: String
    private lateinit var taskFamily: String
    private lateinit var ec2TaskFamily: String
    private lateinit var cluster: String
    private lateinit var vpcId: String
    private lateinit var cfnClient: CloudFormationClient
    private lateinit var deployerEc2Client: Ec2Client
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
        deployerEc2Client = Ec2Client.builder().region(awsRegion).build()

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
        vpcId = deployerEc2Client.describeSubnets { it.subnetIds(subnets.split(",").first()) }
            .subnets().first().vpcId()
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

        if (::deployerEc2Client.isInitialized && ::vpcId.isInitialized) {
            scrubVpcDependencies(vpcId)
        }

        if (::cfnClient.isInitialized) {
            try {
                cfnClient.deleteStack { it.stackName(stackName) }
                try {
                    cfnClient.waiter().waitUntilStackDeleteComplete { it.stackName(stackName) }
                } catch (e: Exception) {
                    val status = runCatching {
                        cfnClient.describeStacks { it.stackName(stackName) }.stacks().firstOrNull()?.stackStatus()
                    }.getOrNull()
                    if (status == StackStatus.DELETE_FAILED && ::deployerEc2Client.isInitialized) {
                        logger.warn("Stack '{}' is DELETE_FAILED — scrubbing remaining VPC dependencies and retrying", stackName)
                        scrubAndDeleteStack()
                    } else {
                        throw e
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to delete stack '{}'", stackName, e)
            }
            cfnClient.close()
        }

        if (::deployerEc2Client.isInitialized) deployerEc2Client.close()
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

    private fun scrubVpcDependencies(vpcId: String) {
        val vpcFilter = Filter.builder().name("vpc-id").values(vpcId).build()

        val endpointIds = deployerEc2Client
            .describeVpcEndpoints { it.filters(vpcFilter) }
            .vpcEndpoints().map { it.vpcEndpointId() }
        if (endpointIds.isNotEmpty()) {
            logger.info("Deleting {} VPC endpoint(s) before stack teardown", endpointIds.size)
            deployerEc2Client.deleteVpcEndpoints { it.vpcEndpointIds(endpointIds) }
            // Wait for endpoints to finish deleting so the subnet becomes free
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                val remaining = deployerEc2Client
                    .describeVpcEndpoints { it.filters(vpcFilter) }
                    .vpcEndpoints().filter { it.stateAsString() != "deleted" }
                if (remaining.isEmpty()) break
                logger.info("Waiting for {} VPC endpoint(s) to finish deleting", remaining.size)
                Thread.sleep(5_000)
            }
        }

        deployerEc2Client.describeSecurityGroups { it.filters(vpcFilter) }
            .securityGroups()
            .filter { it.groupName() != "default" && !it.groupName().startsWith("conductor-integration-test") }
            .forEach { sg ->
                // Retry deletion — external agents (e.g. GuardDuty) may still hold the SG briefly
                var lastException: Exception? = null
                repeat(6) { attempt ->
                    if (lastException != null) Thread.sleep(10_000)
                    try {
                        logger.info("Deleting unmanaged security group {} ({}) attempt {}", sg.groupId(), sg.groupName(), attempt + 1)
                        deployerEc2Client.deleteSecurityGroup { it.groupId(sg.groupId()) }
                        logger.info("Deleted security group {} ({}) successfully", sg.groupId(), sg.groupName())
                        lastException = null
                        return@repeat
                    } catch (e: Exception) {
                        logger.warn("Attempt {} failed to delete security group {} ({}): {}", attempt + 1, sg.groupId(), sg.groupName(), e.message)
                        lastException = e
                    }
                }
                if (lastException != null) {
                    logger.error("Gave up deleting security group {} ({}) after 6 attempts", sg.groupId(), sg.groupName())
                }
            }
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
        val redactedUrl = repositoryUrl.replace(Regex("^\\d+\\."), "<account-id>.")
        logger.info("Deploying stack with image {}/{}", redactedUrl, imageName)

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
            cfnClient.waiter().waitUntilStackCreateComplete { it.stackName(stackName) }
        } catch (e: AlreadyExistsException) {
            val stack = cfnClient.describeStacks { it.stackName(stackName) }.stacks().first()
            when (val status = stack.stackStatus()) {
                StackStatus.CREATE_COMPLETE, StackStatus.UPDATE_COMPLETE ->
                    logger.info("Stack '{}' is {} — reusing existing resources", stackName, status)
                StackStatus.DELETE_FAILED -> {
                    logger.warn("Stack '{}' is DELETE_FAILED — scrubbing VPC dependencies and recreating", stackName)
                    scrubAndDeleteStack()
                    cfnClient.createStack {
                        it.stackName(stackName)
                        it.templateBody(templateBody)
                        it.capabilities(Capability.CAPABILITY_NAMED_IAM)
                        it.parameters(params)
                    }
                    cfnClient.waiter().waitUntilStackCreateComplete { it.stackName(stackName) }
                }
                else -> error("Stack '$stackName' is in unexpected state $status — manual intervention required")
            }
        }
    }

    private fun scrubAndDeleteStack() {
        val nameFilter = Filter.builder().name("tag:Name").values(stackName).build()
        deployerEc2Client.describeVpcs { it.filters(nameFilter) }
            .vpcs()
            .forEach { scrubVpcDependencies(it.vpcId()) }
        cfnClient.deleteStack { it.stackName(stackName) }
        cfnClient.waiter().waitUntilStackDeleteComplete { it.stackName(stackName) }
    }

}

private data class TestContext @JsonCreator constructor(
    @JsonProperty("args") val args: List<String>,
    @JsonProperty("environment") val environment: Map<String, String>
)