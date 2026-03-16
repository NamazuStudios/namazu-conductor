package dev.getelements.conductor.fargate

import dev.getelements.conductor.service.JobProfile

/**
 * [JobProfile] implementation for AWS Fargate. Represents a single ECS task definition family,
 * resolved to its latest active revision at profile-discovery time.
 *
 * The [id] is the task definition family name. [containerName] is the name of the primary container
 * in that task definition and is used when applying environment, command, and argument overrides at
 * execution time.
 */
data class FargateJobProfile(
    val family: String,
    val containerName: String
) : JobProfile {
    override val id: String
        get() = family
}