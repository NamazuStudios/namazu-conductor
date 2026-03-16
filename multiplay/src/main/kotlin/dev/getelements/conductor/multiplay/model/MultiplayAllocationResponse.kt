package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonProperty

data class MultiplayAllocationResponse(
    @JsonProperty("allocationId") val allocationId: String
)