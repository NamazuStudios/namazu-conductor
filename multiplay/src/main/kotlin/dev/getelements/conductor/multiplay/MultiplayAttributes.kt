package dev.getelements.conductor.multiplay

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Attribute name constants for the Multiplay Element. Each constant is the fully-qualified attribute
 * key used by the Elements SDK to bind configuration values via [@Named][com.google.inject.name.Named]
 * injection. Default values are declared on each constant via [@ElementDefaultAttribute].
 */
object MultiplayAttributes {

    /**
     * The service account key ID used for token exchange authentication.
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val KEY_ID = "dev.getelements.conductor.multiplay.key.id"

    /**
     * The service account key secret used for token exchange authentication.
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val KEY_SECRET = "dev.getelements.conductor.multiplay.key.secret"

    /**
     * The Unity project ID (GUID) that owns the Multiplay resources.
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val PROJECT_ID = "dev.getelements.conductor.multiplay.project.id"

    /**
     * The Unity environment ID (GUID) within the project (e.g. production, staging).
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val ENVIRONMENT_ID = "dev.getelements.conductor.multiplay.environment.id"

}