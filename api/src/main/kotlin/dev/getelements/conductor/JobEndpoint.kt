package dev.getelements.conductor

/**
 * Represents an endpoint for a job. This consists of
 */
data class JobEndpoint(val host: String, val port: Int, val protocol: String) {}