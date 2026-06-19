package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A single deployment entry from the `GET /v1/deployments` list response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapDeployment(
    @JsonProperty("request_id")    val requestId: String,
    @JsonProperty("current_status") val status: String,
    @JsonProperty("app_name")      val appName: String? = null,
    @JsonProperty("version_name")  val versionName: String? = null,
    @JsonProperty("fqdn")          val fqdn: String? = null,
    @JsonProperty("public_ip")     val publicIp: String? = null,
    @JsonProperty("ports")         val ports: Map<String, EdgeGapPort> = emptyMap()
)

/**
 * Paginated response from `GET /v1/deployments`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapDeploymentListResponse(
    @JsonProperty("data")       val data: List<EdgeGapDeployment> = emptyList(),
    @JsonProperty("pagination") val pagination: EdgeGapPagination = EdgeGapPagination()
)