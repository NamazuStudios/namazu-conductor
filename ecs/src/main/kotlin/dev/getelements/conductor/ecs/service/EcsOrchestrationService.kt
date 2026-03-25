package dev.getelements.conductor.ecs.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.ecs.EcsAttributes
import dev.getelements.conductor.ecs.EcsJobProfile
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration
import software.amazon.awssdk.services.ecs.model.ContainerOverride
import software.amazon.awssdk.services.ecs.model.KeyValuePair
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration
import software.amazon.awssdk.services.ecs.model.NetworkMode
import software.amazon.awssdk.services.ecs.model.Task
import software.amazon.awssdk.services.ecs.model.TaskDefinitionFamilyStatus
import software.amazon.awssdk.services.ecs.model.TaskDefinitionField
import software.amazon.awssdk.services.ecs.model.TaskOverride
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * [OrchestrationService] implementation backed by AWS ECS via the AWS SDK v2 ECS client.
 *
 * Profiles correspond to active ECS task definition families. Each family is described at
 * discovery time to capture the primary container name, network mode, and launch type. The
 * launch type is read from the `namazu.conductor:launchType` tag on the task definition; if absent,
 * [LaunchType.FARGATE] is used. Network configuration is only applied when the task definition's
 * network mode is [NetworkMode.AWSVPC].
 *
 * All [dev.getelements.conductor.JobPlacement] hints are ignored — task placement is governed
 * entirely by the configured subnets and security groups (for `awsvpc` tasks) or the ECS
 * container instance (for EC2 tasks).
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in [EcsAttributes].
 */
@Singleton
class EcsOrchestrationService @Inject constructor(
    @Named(EcsAttributes.CLUSTER) private val cluster: String,
    @Named(EcsAttributes.SUBNETS) private val subnets: String,
    @Named(EcsAttributes.SECURITY_GROUPS) private val securityGroups: String,
    @Named(EcsAttributes.JOBSET) private val jobSet: String,
    private val ecsClient: EcsClient,
    private val ec2Client: Ec2Client,
    private val executor: ExecutorService
) : OrchestrationService {

    /**
     * Returns one [EcsJobProfile] per active ECS task definition family whose
     * `namazu.conductor:jobSet` tag matches the configured [jobset]. Families without the tag
     * or with a different value are excluded, so each conductor instance only surfaces the task
     * definitions that belong to it.
     */
    override fun getAvailableProfiles(): List<JobProfile> {

        val profiles = mutableListOf<EcsJobProfile>()
        var nextToken: String? = null

        do {
            val response = ecsClient.listTaskDefinitionFamilies {
                it.status(TaskDefinitionFamilyStatus.ACTIVE)
                if (nextToken != null) it.nextToken(nextToken)
            }

            for (family in response.families()) {
                val description = ecsClient.describeTaskDefinition {
                    it.taskDefinition(family)
                    it.include(TaskDefinitionField.TAGS)
                }
                val taskDef = description.taskDefinition()
                val tags = description.tags()

                // Only include task definitions tagged for this jobset.
                val jobsetTag = tags.firstOrNull { it.key() == TAG_JOBSET }?.value()
                if (jobsetTag != jobSet) continue

                val containerName = taskDef.containerDefinitions().firstOrNull()?.name() ?: continue
                val networkMode = taskDef.networkMode() ?: NetworkMode.AWSVPC

                val launchTypeTag = tags.firstOrNull { it.key() == TAG_LAUNCH_TYPE }?.value()
                val launchType = if (launchTypeTag != null) LaunchType.fromValue(launchTypeTag) else LaunchType.FARGATE

                val assignPublicIpTag = tags.firstOrNull { it.key() == TAG_ASSIGN_PUBLIC_IP }?.value()
                val assignPublicIp = if (assignPublicIpTag != null) AssignPublicIp.fromValue(assignPublicIpTag) else AssignPublicIp.DISABLED

                profiles += EcsJobProfile(
                    family = family,
                    containerName = containerName,
                    launchType = launchType,
                    networkMode = networkMode,
                    assignPublicIp = assignPublicIp
                )
            }

            nextToken = response.nextToken()
        } while (nextToken != null)

        return profiles
    }

    /**
     * Polls `describeTasks` on a background thread until the task reaches [status] (or
     * [JobStatus.FAILED]). Once the target status is reached the returned [JobExecution] is
     * populated with [JobEndpoint]s derived from the task's port mappings. The host address used
     * is the public IP when the task definition carries `namazu.conductor:assignPublicIp=ENABLED`,
     * resolved via EC2 `describeNetworkInterfaces`; otherwise the ENI private IP is used.
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
     * Launches an ECS task for the given [JobRequest] and returns a [JobExecution] with
     * status [JobStatus.PENDING].
     *
     * The launch type and network configuration are driven entirely by the [EcsJobProfile].
     * Network configuration is only applied when the task definition uses `awsvpc` network mode.
     *
     * @throws JobException if [JobRequest.profile] is not an [EcsJobProfile], or if ECS does not
     *   return a task ARN in its response.
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? EcsJobProfile
            ?: throw JobException("JobProfile must be a ${EcsJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

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

        val taskResponse = ecsClient.runTask {
            it.cluster(cluster)
            it.taskDefinition(profile.family)
            it.launchType(profile.launchType)
            if (profile.networkMode == NetworkMode.AWSVPC) it.networkConfiguration(buildNetworkConfig(profile.assignPublicIp))
            it.overrides(
                TaskOverride.builder()
                    .containerOverrides(containerOverride)
                    .build()
            )
        }

        val task = taskResponse.tasks().firstOrNull()
            ?: throw JobException("ECS returned no task for family '${profile.family}' on cluster '$cluster'")

        return JobExecution(id = task.taskArn(), status = JobStatus.PENDING)
    }

    override fun stop(execution: JobExecution) {
        ecsClient.stopTask {
            it.cluster(cluster)
            it.task(execution.id)
        }
    }

    private fun buildNetworkConfig(assignPublicIp: AssignPublicIp) = NetworkConfiguration.builder()
        .awsvpcConfiguration(
            AwsVpcConfiguration.builder()
                .subnets(subnets.split(",").map { it.trim() })
                .securityGroups(securityGroups.split(",").map { it.trim() })
                .assignPublicIp(assignPublicIp)
                .build()
        )
        .build()

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
        val eniDetails = task.attachments()
            .firstOrNull { it.type() == "ElasticNetworkInterface" }
            ?.details()
            ?.associateBy { it.name() }
            ?: return emptyList()

        val privateIp = eniDetails["privateIPv4Address"]?.value() ?: return emptyList()
        val eniId = eniDetails["networkInterfaceId"]?.value()

        val taskDef = ecsClient.describeTaskDefinition {
            it.taskDefinition(task.taskDefinitionArn())
            it.include(TaskDefinitionField.TAGS)
        }

        val assignPublicIp = taskDef.tags()
            .firstOrNull { it.key() == TAG_ASSIGN_PUBLIC_IP }
            ?.value()
            ?.let { AssignPublicIp.fromValue(it) }
            ?: AssignPublicIp.DISABLED

        val host = if (assignPublicIp == AssignPublicIp.ENABLED && eniId != null) {
            ec2Client.describeNetworkInterfaces { it.networkInterfaceIds(eniId) }
                .networkInterfaces().firstOrNull()
                ?.association()?.publicIp()
                ?: privateIp
        } else {
            privateIp
        }

        return taskDef.taskDefinition()
            .containerDefinitions()
            .flatMap { container ->
                container.portMappings().map { port ->
                    JobEndpoint(
                        host = host,
                        port = port.hostPort() ?: port.containerPort(),
                        protocol = port.protocolAsString() ?: "tcp"
                    )
                }
            }
    }

    companion object {

        private const val POLL_INTERVAL_MS = 5_000L

        const val TAG_JOBSET = "namazu.conductor:jobSet"

        const val TAG_LAUNCH_TYPE = "namazu.conductor:launchType"

        const val TAG_ASSIGN_PUBLIC_IP = "namazu.conductor:assignPublicIp"

    }

}