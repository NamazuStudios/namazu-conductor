package dev.getelements.conductor.ecs

import dev.getelements.conductor.ContainerRef
import dev.getelements.conductor.service.JobProfile
import software.amazon.awssdk.services.ecs.model.AssignPublicIp
import software.amazon.awssdk.services.ecs.model.LaunchType
import software.amazon.awssdk.services.ecs.model.NetworkMode

/**
 * [JobProfile] implementation for AWS ECS. Represents a single ECS task definition family,
 * resolved to its latest active revision at profile-discovery time.
 *
 * The [id] is the task definition family name. [containerName] is the name of the primary container
 * in that task definition and is used when applying environment, command, and argument overrides at
 * execution time.
 *
     * [launchType] is derived from the `namazu.conductor:launchType` tag on the task definition family,
 * defaulting to [LaunchType.FARGATE] if the tag is absent. [networkMode] is read directly from the
 * task definition and determines whether VPC network configuration is applied at execution time.
 * [assignPublicIp] is derived from the `namazu.conductor:assignPublicIp` tag, defaulting to
 * [AssignPublicIp.DISABLED] if absent.
 */
data class EcsJobProfile(
    val family: String,
    val containerName: String,
    val launchType: LaunchType,
    val networkMode: NetworkMode,
    val assignPublicIp: AssignPublicIp,
    /**
     * The task definition family's **entire** tag map, verbatim and unfiltered — the
     * `namazu.conductor:...` tags this provider interprets as typed fields, and every other tag set
     * on the family, all under their full tag keys. Conductor assigns no meaning to any of them;
     * see [dev.getelements.conductor.Metadata].
     */
    override val metadata: Map<String, String> = emptyMap()
) : JobProfile {
    override val id: String
        get() = family

    /**
     * ECS jobs always run exactly one container; this always has a single primary entry.
     */
    override val containers: List<ContainerRef>
        get() = listOf(ContainerRef(id = containerName, name = containerName, primary = true))
}