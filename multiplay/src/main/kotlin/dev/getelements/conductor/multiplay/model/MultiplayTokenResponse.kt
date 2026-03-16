package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonProperty

data class MultiplayTokenResponse(
    @JsonProperty("accessToken") val accessToken: String
)