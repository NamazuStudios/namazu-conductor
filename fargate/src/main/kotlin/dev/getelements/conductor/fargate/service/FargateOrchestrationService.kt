package dev.getelements.conductor.fargate.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.fargate.FargateAttributes
import dev.getelements.conductor.fargate.FargateJobProfile
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration
import software.amazon.awssdk.services.ecs.model.ContainerOverride
import software.amazon.awssdk.services.ecs.model.KeyValuePair
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration
import software.amazon.awssdk.services.ecs.model.Task
import software.amazon.awssdk.services.ecs.model.TaskDefinitionFamilyStatus
import software.amazon.awssdk.services.ecs.model.TaskOverride
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * [OrchestrationService] implementation backed by AWS Fargate via the AWS SDK v2 ECS client.
 *
 * Profiles correspond to active ECS task definition families. Each family is described at
 * discovery time to capture the primary container name, which is needed for applying
 * [JobRequest] overrides at execution time.
 *
 * All [dev.getelements.conductor.JobPlacement] hints are ignored — Fargate task placement is
 * governed entirely by the configured subnets and security groups.
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in
 * [FargateAttributes].
 */
@Singleton
class FargateOrchestrationService @Inject constructor(
    @Named(FargateAttributes.CLUSTER) private val cluster: String,
    @Named(FargateAttributes.SUBNETS) private val subnets: String,
    @Named(FargateAttributes.SECURITY_GROUPS) private val securityGroups: String,
    @Named(FargateAttributes.ASSIGN_PUBLIC_IP) private val assignPublicIp: String,
    private val ecsClient: EcsClient,
    private val executor: ExecutorService
) : OrchestrationService {

    /**
     * Returns one [FargateJobProfile] per active ECS task definition family. Each family is
     * described to obtain the primary container name required for override application in
     * [execute].
     */
    override fun getAvailableProfiles(): List<JobProfile> {

        val profiles = mutableListOf<FargateJobProfile>()
        var nextToken: String? = null

        do {
            val response = ecsClient.listTaskDefinitionFamilies {
                it.status(TaskDefinitionFamilyStatus.ACTIVE)
                if (nextToken != null) it.nextToken(nextToken)
            }

            for (family in response.families()) {
                val description = ecsClient.describeTaskDefinition { it.taskDefinition(family) }
                val containerName = description.taskDefinition()
                    .containerDefinitions()
                    .firstOrNull()
                    ?.name()
                    ?: continue
                profiles += FargateJobProfile(family = family, containerName = containerName)
            }

            nextToken = response.nextToken()
        } while (nextToken != null)

        return profiles
    }

    /**
     * Polls `describeTasks` on a background thread until the task reaches [status] (or
     * [JobStatus.FAILED]). Once the target status is reached the returned [JobExecution] is
     * populated with [JobEndpoint]s derived from the task's ENI private IP and the port mappings
     * declared in its task definition.
     */
    override fun getFutureForStatus(
        execution: JobExecution,
        status: JobStatus
    ): Future<JobExecution> = CompletableFuture.supplyAsync({
        var result: JobExecution
        do {
            Thread.sleep(POLL_INTERVAL_MS)
            val task = fetchTask(execution.id)
            result = JobExecution(
                id = execution.id,
                status = mapStatus(task),
                endpoints = mapEndpoints(task)
            )
        } while (result.status != status && result.status != JobStatus.FAILED)
        result
    }, executor)

    /**
     * Launches a Fargate task for the given [JobRequest] and returns a [JobExecution] with
     * status [JobStatus.PENDING].
     *
     * [JobRequest.command] and [JobRequest.args] are concatenated and applied as the ECS command
     * override on the primary container. [JobRequest.environment] is applied as environment
     * variable overrides. All [JobRequest.placement] hints are silently ignored.
     *
     * @throws JobException if [JobRequest.profile] is not a [FargateJobProfile], or if Fargate
     *   does not return a task ARN in its response.
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? FargateJobProfile
            ?: throw JobException("JobProfile must be a ${FargateJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

        val envVars = request.environment.map { (k, v) ->
            KeyValuePair.builder().name(k).value(v).build()
        }

        val fullCommand = request.command + request.args

        val containerOverride = ContainerOverride.builder()
            .name(profile.containerName)
            .apply {
                if (fullCommand.isNotEmpty()) command(fullCommand)
                if (envVars.isNotEmpty()) environment(envVars)
            }
            .build()

        val networkConfig = NetworkConfiguration.builder()
            .awsvpcConfiguration(
                AwsVpcConfiguration.builder()
                    .subnets(subnets.split(",").map { it.trim() })
                    .securityGroups(securityGroups.split(",").map { it.trim() })
                    .assignPublicIp(AssignPublicIp.fromValue(assignPublicIp))
                    .build()
            )
            .build()

        val taskResponse = ecsClient.runTask {
            it.cluster(cluster)
            it.taskDefinition(profile.family)
            it.launchType(LaunchType.FARGATE)
            it.networkConfiguration(networkConfig)
            it.overrides(
                TaskOverride.builder()
                    .containerOverrides(containerOverride)
                    .build()
            )
        }

        val task = taskResponse.tasks().firstOrNull()
            ?: throw JobException("Fargate returned no task for family '${profile.family}' on cluster '$cluster'")

        return JobExecution(id = task.taskArn(), status = JobStatus.PENDING)
    }

    private fun fetchTask(taskArn: String): Task {
        val response = ecsClient.describeTasks {
            it.cluster(cluster)
            it.tasks(taskArn)
        }
        return response.tasks().firstOrNull()
            ?: throw JobException("No task found for ARN '$taskArn' on cluster '$cluster'")
    }

    private fun mapStatus(task: Task): JobStatus = when (task.lastStatus()) {
        "RUNNING" -> JobStatus.RUNNING
        "STOPPED" -> {
            val stopCode = task.stopCodeAsString() ?: ""
            if (stopCode.contains("FAILED") || stopCode.contains("ERROR")) JobStatus.FAILED
            else JobStatus.COMPLETED
        }
        "DELETED" -> JobStatus.FAILED
        else -> JobStatus.PENDING
    }

    private fun mapEndpoints(task: Task): List<JobEndpoint> {
        val privateIp = task.attachments()
            .firstOrNull { it.type() == "ElasticNetworkInterface" }
            ?.details()
            ?.firstOrNull { it.name() == "privateIPv4Address" }
            ?.value()
            ?: return emptyList()

        val taskDef = ecsClient.describeTaskDefinition { it.taskDefinition(task.taskDefinitionArn()) }

        return taskDef.taskDefinition()
            .containerDefinitions()
            .flatMap { container ->
                container.portMappings().map { port ->
                    JobEndpoint(
                        host = privateIp,
                        port = port.hostPort() ?: port.containerPort(),
                        protocol = port.protocolAsString() ?: "tcp"
                    )
                }
            }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }

}