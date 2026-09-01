package dev.getelements.conductor.ecs.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.ClusterScope
import dev.getelements.conductor.DaemonExecution
import dev.getelements.conductor.DaemonRequest
import dev.getelements.conductor.DaemonStatus
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.ecs.EcsAttributes
import dev.getelements.conductor.ecs.EcsDaemon
import dev.getelements.conductor.ecs.EcsDaemonExecutionDetails
import dev.getelements.conductor.ecs.EcsExecutionDetails
import dev.getelements.conductor.ecs.EcsJobProfile
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.exception.StdioUnavailableException
import dev.getelements.conductor.service.Daemon
import dev.getelements.conductor.service.DaemonOrchestrationService
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableDimension
import software.amazon.awssdk.services.applicationautoscaling.model.ServiceNamespace
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration
import software.amazon.awssdk.services.ecs.model.ContainerOverride
import software.amazon.awssdk.services.ecs.model.DesiredStatus
import software.amazon.awssdk.services.ecs.model.KeyValuePair
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration
import software.amazon.awssdk.services.ecs.model.NetworkMode
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionResponse
import software.amazon.awssdk.services.ecs.model.Task
import software.amazon.awssdk.services.ecs.model.TaskDefinitionFamilyStatus
import software.amazon.awssdk.services.ecs.model.TaskDefinitionField
import software.amazon.awssdk.services.ecs.model.TaskOverride
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * [OrchestrationService] and [DaemonOrchestrationService] implementation backed by AWS ECS via the
 * AWS SDK v2 ECS client.
 *
 * Job profiles and daemons both correspond to active ECS task definition families, distinguished by
 * the `namazu.conductor:workloadKind` tag (`daemon`, or absent/anything else for one-off jobs) —
 * see [getAvailableProfiles] and [getAvailableDaemons].
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
 * A [ClusterScope] on the request overrides the cluster the task is launched into; the cluster is
 * subsequently recovered from the task ARN (which encodes it) for `stop()` and status polling, so
 * overridden tasks remain trackable without persisting any extra state. Other
 * [dev.getelements.conductor.JobScope] types are silently ignored. Note that [listExecutions]
 * only ever discovers tasks in the configured default cluster, since it has no task ARN to recover
 * an overridden cluster from.
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in [EcsAttributes].
 */
@Singleton
class EcsOrchestrationService @Inject constructor(
    @Named(EcsAttributes.CLUSTER) private val cluster: String,
    @Named(EcsAttributes.SUBNETS) private val subnets: String,
    @Named(EcsAttributes.SECURITY_GROUPS) private val securityGroups: String,
    @Named(EcsAttributes.JOBSET) private val jobSet: String,
    @Named(EcsAttributes.STDIO_BRIDGE_PORT) stdioBridgePort: String = "10080",
    @Named(EcsAttributes.STDIO_BRIDGE_BASE_PATH) private val stdioBridgeBasePath: String = "",
    private val ecsClient: EcsClient,
    private val ec2Client: Ec2Client,
    private val applicationAutoScalingClient: ApplicationAutoScalingClient,
    private val executor: ExecutorService
) : OrchestrationService, DaemonOrchestrationService {

    private val stdioBridgePortNum: Int = stdioBridgePort.toIntOrNull() ?: DEFAULT_STDIO_BRIDGE_PORT

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

                // Daemon-tagged families are surfaced via getAvailableDaemons() instead.
                val workloadKindTag = tags.firstOrNull { it.key() == TAG_WORKLOAD_KIND }?.value()
                if (workloadKindTag == WORKLOAD_KIND_DAEMON) continue

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
     * Returns one [EcsDaemon] per active ECS task definition family whose `namazu.conductor:jobSet`
     * tag matches the configured [jobSet] and whose `namazu.conductor:workloadKind` tag is `"daemon"`.
     * Families without the `daemon` workload kind are surfaced by [getAvailableProfiles] instead —
     * the two are mutually exclusive.
     */
    override fun getAvailableDaemons(): List<Daemon> {

        val daemons = mutableListOf<EcsDaemon>()
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

                val jobsetTag = tags.firstOrNull { it.key() == TAG_JOBSET }?.value()
                if (jobsetTag != jobSet) continue

                val workloadKindTag = tags.firstOrNull { it.key() == TAG_WORKLOAD_KIND }?.value()
                if (workloadKindTag != WORKLOAD_KIND_DAEMON) continue

                val containerName = taskDef.containerDefinitions().firstOrNull()?.name() ?: continue
                val networkMode = taskDef.networkMode() ?: NetworkMode.AWSVPC

                val launchTypeTag = tags.firstOrNull { it.key() == TAG_LAUNCH_TYPE }?.value()
                val launchType = if (launchTypeTag != null) LaunchType.fromValue(launchTypeTag) else LaunchType.FARGATE

                val assignPublicIpTag = tags.firstOrNull { it.key() == TAG_ASSIGN_PUBLIC_IP }?.value()
                val assignPublicIp = if (assignPublicIpTag != null) AssignPublicIp.fromValue(assignPublicIpTag) else AssignPublicIp.DISABLED

                val desiredCountTag = tags.firstOrNull { it.key() == TAG_DESIRED_COUNT }?.value()
                val desiredCount = desiredCountTag?.toIntOrNull()?.takeIf { it >= 0 } ?: 1

                val minCountTag = tags.firstOrNull { it.key() == TAG_MIN_COUNT }?.value()
                val minCount = minCountTag?.toIntOrNull()?.takeIf { it >= 0 }

                val maxCountTag = tags.firstOrNull { it.key() == TAG_MAX_COUNT }?.value()
                val maxCount = maxCountTag?.toIntOrNull()?.takeIf { it >= 0 }

                daemons += EcsDaemon(
                    family = family,
                    containerName = containerName,
                    launchType = launchType,
                    networkMode = networkMode,
                    assignPublicIp = assignPublicIp,
                    desiredCount = desiredCount,
                    minCount = minCount,
                    maxCount = maxCount
                )
            }

            nextToken = response.nextToken()
        } while (nextToken != null)

        return daemons
    }

    /**
     * Deploys an ECS Service for the given [DaemonRequest] and returns a [DaemonExecution] with
     * status [DaemonStatus.PENDING].
     *
     * **[DaemonRequest.command], [DaemonRequest.args], and [DaemonRequest.environment] are ignored.**
     * Unlike [execute]'s `RunTask`, ECS's `CreateService` has no per-call container override
     * mechanism — a Service always runs its task definition exactly as registered. See the ECS
     * provider README's "Daemons" section.
     *
     * If [EcsDaemon.minCount] and [EcsDaemon.maxCount] are both set, an Application Auto Scaling
     * scalable target is registered against the new service's desired-count dimension immediately
     * after creation. No scaling policy is created — the bounds only fence the legal range for
     * [setDesiredCount]; nothing will move the desired count automatically. See [setScalingBounds].
     *
     * @throws JobException if [DaemonRequest.profile] is not an [EcsDaemon], if ECS does not return
     *   a service or service ARN in its response, or if registering the Application Auto Scaling
     *   target fails (most commonly because the account's
     *   `AWSServiceRoleForApplicationAutoScaling_ECSService` service-linked role doesn't exist yet
     *   and the caller lacks `iam:CreateServiceLinkedRole`).
     */
    override fun deploy(request: DaemonRequest): DaemonExecution {
        val profile = request.profile as? EcsDaemon
            ?: throw JobException("Daemon must be a ${EcsDaemon::class.simpleName}; got ${request.profile::class.simpleName}")

        val targetCluster = request.scope.filterIsInstance<ClusterScope>().firstOrNull()?.cluster ?: cluster
        val serviceName = "${profile.family}-${UUID.randomUUID().toString().take(8)}"

        val createResponse = ecsClient.createService {
            it.cluster(targetCluster)
            it.serviceName(serviceName)
            it.taskDefinition(profile.family)
            it.desiredCount(profile.desiredCount)
            it.launchType(profile.launchType)
            if (profile.networkMode == NetworkMode.AWSVPC) it.networkConfiguration(buildNetworkConfig(profile.assignPublicIp))
        }

        val service = createResponse.service()
            ?: throw JobException("ECS returned no service for family '${profile.family}' on cluster '$targetCluster'")

        val serviceArn = service.serviceArn()
            ?: throw JobException("ECS did not return a service ARN for '$serviceName' on cluster '$targetCluster'")

        if (profile.minCount != null && profile.maxCount != null) {
            try {
                applicationAutoScalingClient.registerScalableTarget {
                    it.serviceNamespace(ServiceNamespace.ECS)
                    it.resourceId("service/$targetCluster/$serviceName")
                    it.scalableDimension(ScalableDimension.ECS_SERVICE_DESIRED_COUNT)
                    it.minCapacity(profile.minCount)
                    it.maxCapacity(profile.maxCount)
                }
            } catch (e: Exception) {
                throw JobException(
                    "Failed to register Application Auto Scaling target for service '$serviceName' on " +
                        "cluster '$targetCluster' — if this is the first RegisterScalableTarget call in " +
                        "this account, ensure the caller has iam:CreateServiceLinkedRole so AWS can create " +
                        "AWSServiceRoleForApplicationAutoScaling_ECSService",
                    e
                )
            }
        }

        return DaemonExecution(
            id = serviceArn,
            status = DaemonStatus.PENDING,
            desiredCount = profile.desiredCount,
            runningCount = 0,
            minCount = profile.minCount,
            maxCount = profile.maxCount,
            details = EcsDaemonExecutionDetails(
                cluster = targetCluster,
                serviceName = serviceName,
                taskDefinitionArn = service.taskDefinition() ?: profile.family
            )
        )
    }

    /**
     * Undeploys the ECS Service backing [execution]. Best-effort deregisters any Application Auto
     * Scaling target first (a no-op if none was ever registered), then deletes the service with
     * `force=true` — this stops running tasks immediately rather than draining, consistent with
     * [stop]'s immediate-delete semantics elsewhere in this provider.
     *
     * @throws JobException if no service is found for [execution].
     */
    override fun undeploy(execution: DaemonExecution) {
        val serviceCluster = clusterFromServiceArn(execution.id)
        val serviceName = serviceNameFromArn(execution.id)

        runCatching {
            applicationAutoScalingClient.deregisterScalableTarget {
                it.serviceNamespace(ServiceNamespace.ECS)
                it.resourceId("service/$serviceCluster/$serviceName")
                it.scalableDimension(ScalableDimension.ECS_SERVICE_DESIRED_COUNT)
            }
        }

        val deleteResponse = ecsClient.deleteService {
            it.cluster(serviceCluster)
            it.service(serviceName)
            it.force(true)
        }

        if (deleteResponse.service() == null) {
            throw JobException("No ECS service found for '${execution.id}'")
        }
    }

    /**
     * Returns a fresh snapshot of [execution]'s status via `describeServices`, with [DaemonStatus]
     * mapped from `runningCount`/`desiredCount` the same way as the Kubernetes provider's Deployment
     * status: [DaemonStatus.RUNNING] once `runningCount >= desiredCount`, [DaemonStatus.PENDING]
     * while `runningCount == 0`, [DaemonStatus.DEGRADED] otherwise; a service in `DRAINING`/`INACTIVE`
     * status (e.g. mid- or post-[undeploy]) is reported as [DaemonStatus.FAILED].
     *
     * [DaemonExecution.minCount]/[DaemonExecution.maxCount] reflect the *live* Application Auto
     * Scaling target bounds (if any), not [execution]'s stored values, since [setScalingBounds] may
     * have changed them since [execution] was captured.
     *
     * [DaemonExecution.endpoints] is always empty — a bare `CreateService` with no load balancer
     * attached has no stable network endpoint. Attaching an ALB/NLB target group is out of scope
     * for this provider; see the README's "Daemons" section.
     */
    override fun getStatus(execution: DaemonExecution): DaemonExecution {
        val serviceCluster = clusterFromServiceArn(execution.id)
        val serviceName = serviceNameFromArn(execution.id)

        val describeResponse = ecsClient.describeServices {
            it.cluster(serviceCluster)
            it.services(serviceName)
        }

        val service = describeResponse.services().firstOrNull()
            ?: return execution.copy(status = DaemonStatus.FAILED, runningCount = 0)

        val desiredCount = service.desiredCount()
        val runningCount = service.runningCount()

        val status = when {
            service.status() == "DRAINING" || service.status() == "INACTIVE" -> DaemonStatus.FAILED
            runningCount >= desiredCount && desiredCount > 0 -> DaemonStatus.RUNNING
            runningCount == 0 -> DaemonStatus.PENDING
            else -> DaemonStatus.DEGRADED
        }

        val scalableTarget = runCatching {
            applicationAutoScalingClient.describeScalableTargets {
                it.serviceNamespace(ServiceNamespace.ECS)
                it.resourceIds("service/$serviceCluster/$serviceName")
                it.scalableDimension(ScalableDimension.ECS_SERVICE_DESIRED_COUNT)
            }.scalableTargets().firstOrNull()
        }.getOrNull()

        return DaemonExecution(
            id = execution.id,
            status = status,
            desiredCount = desiredCount,
            runningCount = runningCount,
            minCount = scalableTarget?.minCapacity(),
            maxCount = scalableTarget?.maxCapacity(),
            endpoints = emptyList(),
            details = execution.details
        )
    }

    /**
     * Updates the service's desired count via `updateService`, returning a fresh [getStatus]
     * snapshot. If an Application Auto Scaling target with a scaling policy is active for this
     * service, it may reassert its own desired count on its next reconcile — expected AWS behavior,
     * not a bug. (This provider never creates a scaling policy itself; see [setScalingBounds].)
     */
    override fun setDesiredCount(execution: DaemonExecution, desired: Int): DaemonExecution {
        val serviceCluster = clusterFromServiceArn(execution.id)
        val serviceName = serviceNameFromArn(execution.id)

        ecsClient.updateService {
            it.cluster(serviceCluster)
            it.service(serviceName)
            it.desiredCount(desired)
        }

        return getStatus(execution)
    }

    /**
     * Registers (or updates) an Application Auto Scaling scalable target for the service's
     * desired-count dimension with the given bounds, returning a fresh [getStatus] snapshot.
     * `RegisterScalableTarget` is idempotent, so this single call handles both "register new" and
     * "update existing bounds" — unlike the Kubernetes provider's create-vs-patch HPA split.
     *
     * **No scaling policy is created.** A registered scalable target only fences the legal
     * min/max range for [setDesiredCount] — nothing will automatically move the desired count, unlike
     * the Kubernetes provider's HorizontalPodAutoscaler, which actively scales on CPU utilization out
     * of the box. See the README's "Daemons" section.
     */
    override fun setScalingBounds(execution: DaemonExecution, min: Int, max: Int): DaemonExecution {
        val serviceCluster = clusterFromServiceArn(execution.id)
        val serviceName = serviceNameFromArn(execution.id)

        applicationAutoScalingClient.registerScalableTarget {
            it.serviceNamespace(ServiceNamespace.ECS)
            it.resourceId("service/$serviceCluster/$serviceName")
            it.scalableDimension(ScalableDimension.ECS_SERVICE_DESIRED_COUNT)
            it.minCapacity(min)
            it.maxCapacity(max)
        }

        return getStatus(execution)
    }

    /**
     * Polls `describeTasks` on a background thread until the task reaches [status] (or
     * [JobStatus.FAILED]). Once the target status is reached the returned [JobExecution] is
     * populated with [JobEndpoint]s derived from the task's port mappings. The host address used
     * is the public IP when the task definition carries `namazu.conductor:assignPublicIp=ENABLED`,
     * resolved via EC2 `describeNetworkInterfaces`; otherwise the ENI private IP is used.
     * [execution]'s `details` (including the [streamStdio] token generated in [execute]) is carried
     * forward unchanged, since ECS's `describeTasks` has no notion of it — callers must be able to
     * pass the result straight into [streamStdio].
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
                endpoints = mapEndpoints(task),
                details = execution.details
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
     * A fresh random token is generated per execution and injected as [STDIO_TOKEN_ENV_VAR], so a
     * `namazu-stdio-bridge` sidecar in the task's image (if present) requires it on every
     * connection. The token is carried on the returned [JobExecution]'s
     * [EcsExecutionDetails.stdioToken] for [streamStdio] to present later — this requires no manual
     * configuration by the deployer; it's generated and threaded through automatically.
     *
     * @throws JobException if [JobRequest.profile] is not an [EcsJobProfile], or if ECS does not
     *   return a task ARN in its response.
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? EcsJobProfile
            ?: throw JobException("JobProfile must be a ${EcsJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

        val stdioToken = UUID.randomUUID().toString()

        val envVars = (request.environment + mapOf(STDIO_TOKEN_ENV_VAR to stdioToken)).map { (k, v) ->
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

        val targetCluster = request.scope.filterIsInstance<ClusterScope>().firstOrNull()?.cluster ?: cluster

        val taskResponse = ecsClient.runTask {
            it.cluster(targetCluster)
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
            ?: throw JobException("ECS returned no task for family '${profile.family}' on cluster '$targetCluster'")

        return JobExecution(
            id = task.taskArn(),
            status = JobStatus.PENDING,
            details = EcsExecutionDetails(
                cluster = targetCluster,
                taskDefinitionArn = task.taskDefinitionArn() ?: profile.family,
                launchType = profile.launchType.name,
                lastStatus = task.lastStatus(),
                stdioToken = stdioToken
            )
        )
    }

    override fun listExecutions(): List<JobExecution> {
        val families = getAvailableProfiles().mapTo(mutableSetOf()) { (it as EcsJobProfile).family }
        val executions = mutableListOf<JobExecution>()
        for (family in families) {
            var nextToken: String? = null
            do {
                val listResponse = ecsClient.listTasks {
                    it.cluster(cluster)
                    it.family(family)
                    it.desiredStatus(DesiredStatus.RUNNING)
                    if (nextToken != null) it.nextToken(nextToken)
                }
                val arns = listResponse.taskArns()
                if (arns.isNotEmpty()) {
                    ecsClient.describeTasks {
                        it.cluster(cluster)
                        it.tasks(arns)
                    }.tasks().forEach { task ->
                        executions += JobExecution(
                            id = task.taskArn(),
                            status = mapStatus(task),
                            endpoints = mapEndpoints(task),
                            details = EcsExecutionDetails(
                                cluster = cluster,
                                taskDefinitionArn = task.taskDefinitionArn() ?: "",
                                launchType = task.launchTypeAsString(),
                                lastStatus = task.lastStatus()
                            )
                        )
                    }
                }
                nextToken = listResponse.nextToken()
            } while (nextToken != null)
        }
        return executions
    }

    override fun stop(execution: JobExecution) {
        ecsClient.stopTask {
            it.cluster(clusterFromArn(execution.id))
            it.task(execution.id)
        }
    }

    /**
     * Opens a live, bidirectional stdio session for [execution] by connecting to a
     * `namazu-stdio-bridge` sidecar assumed to be listening on [stdioBridgePortNum] within the
     * task's container, at the same host resolved for [JobEndpoint]s. ECS has no native container
     * stdio API, so this requires the task's image to include the bridge (see
     * `stdio-bridge/README.md`) with its port declared in the container's port mappings.
     *
     * Presents the token [execute] generated and injected into the task's container environment as
     * the bridge's required `Authorization` bearer token — read from [execution]'s
     * [EcsExecutionDetails.stdioToken], so [execution] must be the [JobExecution] originally
     * returned by [execute], or one derived from it via [getFutureForStatus]/[getStageForStatus]
     * (both carry `details` forward); an execution reconstructed from [listExecutions] instead has
     * no token available and can't authenticate.
     *
     * @throws StdioUnavailableException if [execution] has no [EcsExecutionDetails.stdioToken], no
     *   reachable host yet, or the bridge can't be reached (not present in the image, port not
     *   mapped, wrong token, etc.)
     */
    override fun streamStdio(execution: JobExecution): JobStdio {
        val token = (execution.details as? EcsExecutionDetails)?.stdioToken
            ?: throw StdioUnavailableException(
                "No stdio token available for execution '${execution.id}' — streamStdio requires " +
                    "the JobExecution originally returned by execute() (or one derived from it via " +
                    "getFutureForStatus/getStageForStatus), not one reconstructed from listExecutions()"
            )

        val task = fetchTask(execution.id)
        val taskDef = ecsClient.describeTaskDefinition {
            it.taskDefinition(task.taskDefinitionArn())
            it.include(TaskDefinitionField.TAGS)
        }
        val host = resolveHost(task, taskDef)
            ?: throw StdioUnavailableException("No reachable host for execution '${execution.id}' yet")

        return StdioBridgeClient.connect(host, stdioBridgePortNum, stdioBridgeBasePath, token)
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
        val taskCluster = clusterFromArn(taskArn)
        val response = ecsClient.describeTasks {
            it.cluster(taskCluster)
            it.tasks(taskArn)
        }
        return response.tasks().firstOrNull()
            ?: throw JobException("No task found for ARN '$taskArn' on cluster '$taskCluster'")
    }

    /**
     * Recovers the cluster name from a task ARN (`arn:aws:ecs:region:account:task/cluster/task-id`),
     * falling back to the configured default [cluster] if the ARN doesn't carry one (e.g. the
     * deprecated short ARN format). This lets tasks launched into a [ClusterScope]-overridden
     * cluster remain trackable via [stop] and status polling without persisting any extra state.
     */
    private fun clusterFromArn(taskArn: String): String =
        taskArn.substringAfter(":task/", "").substringBefore("/").ifEmpty { cluster }

    /**
     * Recovers the cluster name from a service ARN (`arn:aws:ecs:region:account:service/cluster/service-name`),
     * falling back to the configured default [cluster] if the ARN doesn't carry one, mirroring
     * [clusterFromArn]'s handling of task ARNs.
     */
    private fun clusterFromServiceArn(serviceArn: String): String =
        serviceArn.substringAfter(":service/", "").substringBefore("/").ifEmpty { cluster }

    private fun serviceNameFromArn(serviceArn: String): String =
        serviceArn.substringAfterLast("/")

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
        val taskDef = ecsClient.describeTaskDefinition {
            it.taskDefinition(task.taskDefinitionArn())
            it.include(TaskDefinitionField.TAGS)
        }
        val host = resolveHost(task, taskDef) ?: return emptyList()

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

    private fun resolveHost(task: Task, taskDef: DescribeTaskDefinitionResponse): String? =
        if (taskDef.taskDefinition().networkMode() == NetworkMode.AWSVPC) {
            resolveAwsVpcHost(task, taskDef)
        } else {
            resolveContainerInstanceHost(task)
        }

    private fun resolveAwsVpcHost(task: Task, taskDef: DescribeTaskDefinitionResponse): String? {
        val eniDetails = task.attachments()
            .firstOrNull { it.type() == "ElasticNetworkInterface" }
            ?.details()
            ?.associateBy { it.name() }
            ?: return null

        val privateIp = eniDetails["privateIPv4Address"]?.value() ?: return null
        val eniId = eniDetails["networkInterfaceId"]?.value()

        val assignPublicIp = taskDef.tags()
            .firstOrNull { it.key() == TAG_ASSIGN_PUBLIC_IP }
            ?.value()
            ?.let { AssignPublicIp.fromValue(it) }
            ?: AssignPublicIp.DISABLED

        return if (assignPublicIp == AssignPublicIp.ENABLED && eniId != null) {
            ec2Client.describeNetworkInterfaces { it.networkInterfaceIds(eniId) }
                .networkInterfaces().firstOrNull()
                ?.association()?.publicIp()
                ?: privateIp
        } else {
            privateIp
        }
    }

    private fun resolveContainerInstanceHost(task: Task): String? {
        val containerInstanceArn = task.containerInstanceArn() ?: return null

        val ec2InstanceId = ecsClient.describeContainerInstances {
            it.cluster(clusterFromArn(task.taskArn()))
            it.containerInstances(containerInstanceArn)
        }.containerInstances().firstOrNull()?.ec2InstanceId() ?: return null

        val instance = ec2Client.describeInstances {
            it.instanceIds(ec2InstanceId)
        }.reservations().firstOrNull()?.instances()?.firstOrNull() ?: return null

        return instance.publicIpAddress() ?: instance.privateIpAddress()
    }

    companion object {

        private const val POLL_INTERVAL_MS = 5_000L

        const val TAG_JOBSET = "namazu.conductor:jobSet"

        const val TAG_LAUNCH_TYPE = "namazu.conductor:launchType"

        const val TAG_ASSIGN_PUBLIC_IP = "namazu.conductor:assignPublicIp"

        /** Distinguishes daemon (ECS Service) task definitions from one-off job task definitions. */
        const val TAG_WORKLOAD_KIND = "namazu.conductor:workloadKind"

        /** [TAG_WORKLOAD_KIND] value that marks a family as daemon-eligible. */
        const val WORKLOAD_KIND_DAEMON = "daemon"

        const val TAG_DESIRED_COUNT = "namazu.conductor:desiredCount"

        const val TAG_MIN_COUNT = "namazu.conductor:minCount"

        const val TAG_MAX_COUNT = "namazu.conductor:maxCount"

        private const val DEFAULT_STDIO_BRIDGE_PORT = 10080

        /** Matches namazu-stdio-bridge's own required `NAMAZU_CONDUCTOR_STDIO_TOKEN` env var. */
        private const val STDIO_TOKEN_ENV_VAR = "NAMAZU_CONDUCTOR_STDIO_TOKEN"

    }

}