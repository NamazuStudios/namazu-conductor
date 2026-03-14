package dev.getelements.conductor

/**
 * Indicates the strategy used to place a job on the orchestration layer. Different orchestration
 * backends support different placement types — unsupported types are silently ignored by the
 * [dev.getelements.conductor.service.OrchestrationService] implementation.
 */
enum class PlacementType {

    /**
     * Placement by named region code. Supported by Hathora and Multiplay. The exact region string
     * values are specific to the orchestrator implementation.
     */
    REGION,

    /**
     * Placement by a specific IP address. Useful when directing traffic to a known server instance.
     */
    IP_ADDRESS,

    /**
     * Placement by GPS coordinates (latitude/longitude). Used by EdgeGap to route jobs to the
     * geographically nearest available server.
     */
    LAT_LON,
}

/**
 * Marker interface for job placement hints supplied in a [JobRequest]. Each implementation
 * corresponds to a [PlacementType] and carries the data required by the orchestration backend
 * to honour that placement strategy.
 */
interface JobPlacement{
    val type: PlacementType
}

/**
 * Specifies placement by a named region code. Supported by orchestrators such as Hathora and
 * Multiplay. The meaning of [id] is platform-specific.
 */
data class RegionPlacement(val id : String) : JobPlacement {

    /**
     * Always returns [PlacementType.REGION].
     */
    override val type: PlacementType
        get() = PlacementType.REGION

}

/**
 * Specifies placement by an explicit IP address, directing the job to a specific server instance.
 */
data class IpPlacement(val ip : String) : JobPlacement {

    /**
     * Always returns [PlacementType.IP_ADDRESS].
     */
    override val type: PlacementType
        get() = PlacementType.IP_ADDRESS

}

/**
 * Specifies placement by GPS coordinates. Used by EdgeGap to select the geographically nearest
 * available server to the supplied [latitude] and [longitude].
 */
data class LatitudeLongitudePlacement(val latitude : Double, val longitude : Double) : JobPlacement {

    /**
     * Always returns [PlacementType.LAT_LON].
     */
    override val type: PlacementType
        get() = PlacementType.LAT_LON

}