package dev.getelements.conductor.edgegap

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Attribute name constants for the EdgeGap Element. Each constant is the fully-qualified attribute
 * key used by the Elements SDK to bind configuration values via [@Named][com.google.inject.name.Named]
 * injection. Default values are declared on each constant via [@ElementDefaultAttribute].
 */
object EdgeGapAttributes {

    /**
     * The EdgeGap API key used to authenticate all requests. Passed as the bare value in the
     * `Authorization` header. No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute(sensitive = true)
    const val API_KEY = "dev.getelements.conductor.edgegap.api.key"

    /**
     * The base URL for the EdgeGap REST API. Defaults to the EdgeGap production endpoint.
     * Override for testing or regional mirrors.
     */
    @ElementDefaultAttribute("https://api.edgegap.com")
    const val BASE_URL = "dev.getelements.conductor.edgegap.base.url"

    /**
     * Indicates the polling interval for checking when an EdgeGap instance starts.
     */
    @ElementDefaultAttribute("dev.getelements.conductor.edgegap.poll.interval.ms")
    const val POLL_INTERVAL = "5000";

    /**
     * Port on which a `namazu-stdio-bridge` sidecar (if included in the app version's container
     * image) listens for stdio WebSocket connections. EdgeGap has no native container stdio API, so
     * [dev.getelements.conductor.edgegap.service.EdgeGapOrchestrationService.streamStdio] depends on
     * this bridge; declare this port in the app version's port mapping for it to be reachable.
     */
    @ElementDefaultAttribute("10080")
    const val STDIO_BRIDGE_PORT = "dev.getelements.conductor.edgegap.stdio.bridge.port"

    /**
     * Base path prefix for the `namazu-stdio-bridge` WebSocket endpoints — must match the bridge's
     * own `NAMAZU_CONDUCTOR_STDIO_URI`. Defaults to no prefix.
     */
    @ElementDefaultAttribute("")
    const val STDIO_BRIDGE_BASE_PATH = "dev.getelements.conductor.edgegap.stdio.bridge.base.path"

}