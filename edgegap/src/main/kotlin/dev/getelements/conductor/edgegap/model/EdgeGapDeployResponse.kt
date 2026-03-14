package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Response body from `POST /v1/deploy`. The [requestId] is the stable identifier for the
 * deployment used in all subsequent status and stop calls.
 *
 * @property requestId the deployment identifier (e.g. `"61b1be8f54d9"`)
 * @property requestDns the stable FQDN assigned to this deployment (available immediately,
 *   before the container is ready)
 */
data class EdgeGapDeployResponse(
    @JsonProperty("request_id") val requestId: String,
    @JsonProperty("request_dns") val requestDns: String? = null
)
