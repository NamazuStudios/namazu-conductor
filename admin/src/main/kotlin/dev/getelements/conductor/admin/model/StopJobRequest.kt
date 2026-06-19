package dev.getelements.conductor.admin.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class StopJobRequest @JsonCreator constructor(
    @JsonProperty("element") val element: String,
    @JsonProperty("id")      val id: String
)