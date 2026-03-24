package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A single application entry from the `GET /v1/apps` list response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapApp(
    @JsonProperty("name") val name: String,
    @JsonProperty("is_active") val isActive: Boolean = false
)

/**
 * Paginated response from `GET /v1/apps`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapAppsResponse(
    @JsonProperty("applications") val data: List<EdgeGapApp>? = null,
    @JsonProperty("pagination") val pagination: EdgeGapPagination = EdgeGapPagination()
)
