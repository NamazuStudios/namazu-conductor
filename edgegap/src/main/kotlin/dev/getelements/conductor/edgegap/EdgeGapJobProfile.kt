package dev.getelements.conductor.edgegap

import dev.getelements.conductor.service.JobProfile

/**
 * An [EdgeGap](https://edgegap.com)-specific [JobProfile] that identifies a deployable app version
 * by its [appName] and [versionName] as they appear in the EdgeGap platform.
 *
 * Instances are returned by [dev.getelements.conductor.edgegap.service.EdgeGapOrchestrationService.getAvailableProfiles]
 * and must be passed back unmodified when submitting a
 * [dev.getelements.conductor.JobRequest] to that same service.
 *
 * @property appName the EdgeGap application name (slug)
 * @property versionName the EdgeGap application version name
 */
data class EdgeGapJobProfile(
    val appName: String,
    val versionName: String
) : JobProfile {

    /**
     * Composite identifier formatted as `"$appName:$versionName"`.
     */
    override val id: String
        get() = "$appName:$versionName"

}