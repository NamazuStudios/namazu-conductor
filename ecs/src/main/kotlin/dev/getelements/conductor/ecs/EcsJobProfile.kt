package dev.getelements.conductor.ecs

import dev.getelements.conductor.service.JobProfile
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
 * [launchType] is derived from the `conductor:launchType` tag on the task definition family, defaulting
 * to [LaunchType.FARGATE] if the tag is absent. [networkMode] is read directly from the task definition
 * and determines whether VPC network configuration is applied at execution time.
 */
data class EcsJobProfile(
    val family: String,
    val containerName: String,
    val launchType: LaunchType,
    val networkMode: NetworkMode
) : JobProfile {
    override val id: String
        get() = family
}