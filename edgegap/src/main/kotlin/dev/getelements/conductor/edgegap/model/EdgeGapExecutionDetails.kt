package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for an EdgeGap [dev.getelements.conductor.JobExecution].
 */
data class EdgeGapExecutionDetails(
    @JsonProperty("appName")    val appName: String? = null,
    @JsonProperty("versionName") val versionName: String? = null,
    @JsonProperty("fqdn")       val fqdn: String? = null,
    @JsonProperty("publicIp")   val publicIp: String? = null,

    /**
     * The per-execution `namazu-stdio-bridge` access token generated in
     * [dev.getelements.conductor.edgegap.service.EdgeGapOrchestrationService.execute], carried
     * forward so [dev.getelements.conductor.edgegap.service.EdgeGapOrchestrationService.streamStdio]
     * can present it later. `@JsonIgnore`d — [dev.getelements.conductor.JobExecution.details] is
     * "serialised as-is by the REST layer," and this is a secret, not something to expose there.
     */
    @JsonIgnore
    val stdioToken: String? = null
)