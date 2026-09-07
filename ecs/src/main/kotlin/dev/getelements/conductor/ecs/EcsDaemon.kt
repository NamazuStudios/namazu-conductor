package dev.getelements.conductor.ecs

import dev.getelements.conductor.service.Daemon
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkMode

/**
 * [Daemon] implementation for AWS ECS. Represents a single ECS task definition family tagged for
 * daemon (ECS Service) discovery, resolved to its latest active revision at discovery time.
 *
 * The [id] is the task definition family name. [containerName] is the name of the primary container
 * in that task definition.
 *
 * [launchType] is derived from the `namazu.conductor:launchType` tag, defaulting to
 * [LaunchType.FARGATE] if absent. [networkMode] is read directly from the task definition.
 * [assignPublicIp] is derived from the `namazu.conductor:assignPublicIp` tag, defaulting to
 * [AssignPublicIp.DISABLED] if absent. [desiredCount] is derived from the `namazu.conductor:desiredCount`
 * tag, defaulting to `1`. [minCount]/[maxCount] are derived from the `namazu.conductor:minCount`/
 * `namazu.conductor:maxCount` tags; if either is absent, no Application Auto Scaling target is
 * registered when this daemon is deployed.
 */
data class EcsDaemon(
    val family: String,
    val containerName: String,
    val launchType: LaunchType,
    val networkMode: NetworkMode,
    val assignPublicIp: AssignPublicIp,
    val desiredCount: Int = 1,
    val minCount: Int? = null,
    val maxCount: Int? = null
) : Daemon {
    override val id: String
        get() = family
}
