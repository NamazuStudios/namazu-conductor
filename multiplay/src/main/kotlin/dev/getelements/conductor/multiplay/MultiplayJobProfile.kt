package dev.getelements.conductor.multiplay

import dev.getelements.conductor.service.JobProfile

/**
 * [JobProfile] implementation for Unity Multiplay. Represents a single build configuration
 * within a fleet, which together identify the exact server type and pool to allocate from.
 *
 * The [id] is formatted as `"$fleetId:$buildConfigurationId"`. Both components are needed at
 * execution time: the fleet ID routes to the correct server pool and the build configuration ID
 * selects the server image and settings within that pool.
 */
data class MultiplayJobProfile(
    val fleetId: String,
    val buildConfigurationId: String,
    val name: String
) : JobProfile {
    override val id: String
        get() = "$fleetId:$buildConfigurationId"
}