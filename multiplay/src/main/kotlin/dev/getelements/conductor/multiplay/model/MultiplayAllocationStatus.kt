package dev.getelements.conductor.multiplay.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Response body from `GET /v1/allocations/projects/{project}/environments/{env}/allocations/{allocationId}`.
 *
 * @property allocationId the allocation identifier
 * @property status Multiplay lifecycle status string (e.g. `"PENDING"`, `"ALLOCATED"`, `"FAILED"`)
 * @property ipAddress the public IP address of the allocated server, available once [status] is `"ALLOCATED"`
 * @property gamePort the port binding exposed by the server, available once [status] is `"ALLOCATED"`
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MultiplayAllocationStatus(
    @JsonProperty("allocationId") val allocationId: String,
    @JsonProperty("status") val status: String,
    @JsonProperty("ipAddress") val ipAddress: String? = null,
    @JsonProperty("gamePort") val gamePort: MultiplayGamePort? = null
)

/**
 * A single port binding within [MultiplayAllocationStatus].
 *
 * @property name logical name of the port (e.g. `"default"`)
 * @property port the port number reachable by clients
 * @property protocol transport protocol (e.g. `"UDP"`, `"TCP"`)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MultiplayGamePort(
    @JsonProperty("name") val name: String,
    @JsonProperty("port") val port: Int,
    @JsonProperty("protocol") val protocol: String
)