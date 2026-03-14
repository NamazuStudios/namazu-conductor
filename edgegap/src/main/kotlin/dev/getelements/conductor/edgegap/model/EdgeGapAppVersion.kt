package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A single app version entry from `GET /v1/app/{app_name}/version/{version_name}` or the
 * corresponding list endpoint. Each active version is surfaced as an
 * [dev.getelements.conductor.edgegap.EdgeGapJobProfile].
 */
data class EdgeGapAppVersion(
    @JsonProperty("name") val name: String,
    @JsonProperty("is_active") val isActive: Boolean = false,
    @JsonProperty("docker_repository") val dockerRepository: String? = null,
    @JsonProperty("docker_image") val dockerImage: String? = null,
    @JsonProperty("docker_tag") val dockerTag: String? = null
)

/**
 * Paginated response from `GET /v1/app/{app_name}/versions`.
 */
data class EdgeGapAppVersionList(
    @JsonProperty("data") val data: List<EdgeGapAppVersion> = emptyList(),
    @JsonProperty("pagination") val pagination: EdgeGapPagination = EdgeGapPagination()
)
