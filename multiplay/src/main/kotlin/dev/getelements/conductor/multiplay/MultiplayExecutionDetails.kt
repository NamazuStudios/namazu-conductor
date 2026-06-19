package dev.getelements.conductor.multiplay

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for a Multiplay [dev.getelements.conductor.JobExecution].
 */
data class MultiplayExecutionDetails(
    @JsonProperty("fleetId")             val fleetId: String? = null,
    @JsonProperty("buildConfigurationId") val buildConfigurationId: String? = null,
    @JsonProperty("regionId")            val regionId: String? = null
)