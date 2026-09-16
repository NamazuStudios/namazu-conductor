package dev.getelements.conductor.admin.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class TerminalTicketRequest @JsonCreator constructor(
    @JsonProperty("jobId")       val jobId: String,
    @JsonProperty("containerId") val containerId: String?,
    @JsonProperty("command")     val command: List<String>?
)

data class TerminalTicketResponse(val ticket: String)
