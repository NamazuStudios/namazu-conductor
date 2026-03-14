package dev.getelements.conductor.edgegap

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Attribute name constants for the EdgeGap Element. Each constant is the fully-qualified attribute
 * key used by the Elements SDK to bind configuration values via [@Named][com.google.inject.name.Named]
 * injection. Default values are declared on each constant via [@ElementDefaultAttribute].
 */
object EdgeGapAttributes {

    /**
     * The EdgeGap API token used to authenticate all requests. Supplied as the literal value after
     * `"token "` in the `Authorization` header per the EdgeGap authentication scheme.
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val API_KEY = "dev.getelements.conductor.edgegap.api.key"

    /**
     * The base URL for the EdgeGap REST API. Defaults to the EdgeGap production endpoint.
     * Override for testing or regional mirrors.
     */
    @ElementDefaultAttribute("https://api.edgegap.com")
    const val BASE_URL = "dev.getelements.conductor.edgegap.base.url"

}