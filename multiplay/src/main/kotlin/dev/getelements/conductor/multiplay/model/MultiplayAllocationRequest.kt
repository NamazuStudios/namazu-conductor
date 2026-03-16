package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MultiplayAllocationRequest(
    @JsonProperty("buildConfigurationId") val buildConfigurationId: String,
    @JsonProperty("regionId") val regionId: String? = null
)