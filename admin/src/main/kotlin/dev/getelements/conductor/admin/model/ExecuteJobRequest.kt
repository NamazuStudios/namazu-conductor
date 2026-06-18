package dev.getelements.conductor.admin.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import dev.getelements.conductor.IpPlacement
import dev.getelements.conductor.JobPlacement
import dev.getelements.conductor.LatitudeLongitudePlacement
import dev.getelements.conductor.PlacementType
import dev.getelements.conductor.RegionPlacement

data class ExecuteJobRequest @JsonCreator constructor(
    @JsonProperty("element")     val element: String,
    @JsonProperty("profileId")   val profileId: String,
    @JsonProperty("args")        val args: List<String>?,
    @JsonProperty("command")     val command: List<String>?,
    @JsonProperty("environment") val environment: Map<String, String>?,
    @JsonProperty("placement")   val placement: List<PlacementDto>?
)

data class PlacementDto @JsonCreator constructor(
    @JsonProperty("type")      val type: PlacementType,
    @JsonProperty("region")    val region: String?,
    @JsonProperty("ip")        val ip: String?,
    @JsonProperty("latitude")  val latitude: Double?,
    @JsonProperty("longitude") val longitude: Double?
) {
    fun toPlacement(): JobPlacement = when (type) {
        PlacementType.REGION    -> RegionPlacement(region
            ?: throw IllegalArgumentException("region is required for REGION placement"))
        PlacementType.IP_ADDRESS -> IpPlacement(ip
            ?: throw IllegalArgumentException("ip is required for IP_ADDRESS placement"))
        PlacementType.LAT_LON   -> LatitudeLongitudePlacement(
            latitude  ?: throw IllegalArgumentException("latitude is required for LAT_LON placement"),
            longitude ?: throw IllegalArgumentException("longitude is required for LAT_LON placement")
        )
    }
}