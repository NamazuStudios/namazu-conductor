package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonProperty

data class MultiplayBuildConfiguration(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String
)

data class MultiplayBuildConfigurationPage(
    @JsonProperty("results") val results: List<MultiplayBuildConfiguration> = emptyList(),
    @JsonProperty("total") val total: Int = 0
)