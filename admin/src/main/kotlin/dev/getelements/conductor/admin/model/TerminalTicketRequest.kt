package dev.getelements.conductor.admin.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class TerminalTicketRequest @JsonCreator constructor(
    @JsonProperty("jobId")       val jobId: String,
    @JsonProperty("containerId") val containerId: String?
)

data class TerminalTicketResponse(val ticket: String)
