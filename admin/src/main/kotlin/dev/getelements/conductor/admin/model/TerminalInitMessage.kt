package dev.getelements.conductor.admin.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The first WebSocket text frame a terminal client must send, authorizing the connection and
 * optionally overriding the command to exec instead of the container's default (e.g. a shell). See
 * [dev.getelements.conductor.admin.ws.TerminalSessionHandler] for how this replaces the old
 * REST-minted-ticket handshake.
 */
data class TerminalInitMessage @JsonCreator constructor(
    @JsonProperty("sessionSecret") val sessionSecret: String,
    @JsonProperty("command")       val command: List<String>?
)
