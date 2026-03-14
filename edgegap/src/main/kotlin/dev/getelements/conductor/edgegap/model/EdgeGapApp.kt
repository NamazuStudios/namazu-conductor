package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A single application entry from the `GET /v1/apps` list response.
 */
data class EdgeGapApp(
    @JsonProperty("name") val name: String,
    @JsonProperty("is_active") val isActive: Boolean = false
)

/**
 * Paginated response from `GET /v1/apps`.
 */
data class EdgeGapAppsResponse(
    @JsonProperty("data") val data: List<EdgeGapApp> = emptyList(),
    @JsonProperty("pagination") val pagination: EdgeGapPagination = EdgeGapPagination()
)
