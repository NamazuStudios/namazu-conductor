package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Paginated response from the Multiplay allocations list endpoint.
 */
data class MultiplayAllocationPage(
    @JsonProperty("results") val results: List<MultiplayAllocationStatus> = emptyList(),
    @JsonProperty("total")   val total: Int = 0
)