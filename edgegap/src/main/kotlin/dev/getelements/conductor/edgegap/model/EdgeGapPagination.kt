package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Pagination metadata returned by all EdgeGap list endpoints.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeGapPagination(
    @JsonProperty("number") val number: Int = 1,
    @JsonProperty("has_next") val hasNext: Boolean = false,
    @JsonProperty("has_previous") val hasPrevious: Boolean = false
)
