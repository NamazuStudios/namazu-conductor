package dev.getelements.conductor.edgegap.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * An environment variable to inject into the container at deployment time.
 */
data class EdgeGapEnvVar(
    @JsonProperty("key") val key: String,
    @JsonProperty("value") val value: String,
    @JsonProperty("is_hidden") val isHidden: Boolean = false
)

/**
 * A geo-coordinate entry for the `geo_ip_list` field of [EdgeGapDeployRequest]. EdgeGap uses these
 * to select the geographically nearest available host. The [ip] field is optional; when omitted
 * EdgeGap uses the coordinates alone for proximity matching.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class EdgeGapGeoIp(
    @JsonProperty("ip") val ip: String? = null,
    @JsonProperty("latitude") val latitude: Double,
    @JsonProperty("longitude") val longitude: Double
)

/**
 * Request body for `POST /v1/deploy`. Fields that are empty or null are omitted from the
 * serialized JSON to keep the payload minimal and avoid overriding EdgeGap platform defaults.
 *
 * @property appName the EdgeGap application slug
 * @property versionName the specific version to deploy; omit to auto-select the latest active version
 * @property ipList client IP addresses used for geo-proximity host selection via [PlacementType.IP_ADDRESS]
 * @property geoIpList coordinate pairs used for geo-proximity host selection via [PlacementType.LAT_LON]
 * @property envVars runtime environment variable overrides
 * @property command overrides the container's default entrypoint command
 * @property arguments arguments appended to the container entrypoint
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class EdgeGapDeployRequest(
    @JsonProperty("app_name") val appName: String,
    @JsonProperty("version_name") val versionName: String,
    @JsonProperty("ip_list") val ipList: List<String> = emptyList(),
    @JsonProperty("geo_ip_list") val geoIpList: List<EdgeGapGeoIp> = emptyList(),
    @JsonProperty("env_vars") val envVars: List<EdgeGapEnvVar> = emptyList(),
    @JsonProperty("command") val command: String? = null,
    @JsonProperty("arguments") val arguments: String? = null
)
