package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Response body from `GET /v1/status/{request_id}`.
 *
 * @property requestId the deployment identifier
 * @property status EdgeGap lifecycle status string (e.g. `"Status.RUNNING"`, `"Status.TERMINATED"`)
 * @property fqdn the stable fully-qualified domain name assigned to the deployment
 * @property publicIp the public IP address of the host running the deployment
 * @property ports map of logical port name to [EdgeGapPort] describing the external binding
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapStatusResponse(
    @JsonProperty("request_id") val requestId: String,
    @JsonProperty("status") val status: String,
    @JsonProperty("fqdn") val fqdn: String? = null,
    @JsonProperty("public_ip") val publicIp: String? = null,
    @JsonProperty("ports") val ports: Map<String, EdgeGapPort> = emptyMap()
)

/**
 * A single port binding returned within [EdgeGapStatusResponse.ports].
 *
 * @property external the port number reachable by clients from the public internet
 * @property internal the port the container is listening on internally
 * @property protocol transport protocol (e.g. `"UDP"`, `"TCP"`)
 * @property tls whether TLS termination is applied to this port
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapPort(
    @JsonProperty("external") val external: Int,
    @JsonProperty("internal") val internal: Int,
    @JsonProperty("protocol") val protocol: String,
    @JsonProperty("tls") val tls: Boolean = false
)