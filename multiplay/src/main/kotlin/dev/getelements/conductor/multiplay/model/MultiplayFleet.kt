package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonProperty

data class MultiplayFleet(
    @JsonProperty("fleetId") val fleetId: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("buildConfigurations") val buildConfigurations: List<String> = emptyList()
)

data class MultiplayFleetPage(
    @JsonProperty("results") val results: List<MultiplayFleet> = emptyList(),
    @JsonProperty("total") val total: Int = 0
)
