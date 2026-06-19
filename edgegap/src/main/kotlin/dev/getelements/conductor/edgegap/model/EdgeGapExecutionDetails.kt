package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for an EdgeGap [dev.getelements.conductor.JobExecution].
 */
data class EdgeGapExecutionDetails(
    @JsonProperty("appName")    val appName: String? = null,
    @JsonProperty("versionName") val versionName: String? = null,
    @JsonProperty("fqdn")       val fqdn: String? = null,
    @JsonProperty("publicIp")   val publicIp: String? = null
)