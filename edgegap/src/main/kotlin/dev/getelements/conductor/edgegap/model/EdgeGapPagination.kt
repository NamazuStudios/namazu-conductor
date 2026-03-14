package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Pagination metadata returned by all EdgeGap list endpoints.
 */
data class EdgeGapPagination(
    @JsonProperty("number") val number: Int = 1,
    @JsonProperty("has_next") val hasNext: Boolean = false,
    @JsonProperty("has_previous") val hasPrevious: Boolean = false
)
