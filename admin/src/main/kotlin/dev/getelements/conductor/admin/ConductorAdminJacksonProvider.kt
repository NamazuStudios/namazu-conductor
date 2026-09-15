package dev.getelements.conductor.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import jakarta.ws.rs.ext.Provider

/**
 * Without an explicitly registered Jackson provider, the platform's default JSON handling for this
 * Element serializes responses fine (plain getters, no creator needed) but cannot deserialize request
 * bodies into Kotlin data classes at all — even fully `@JsonCreator`/`@JsonProperty`-annotated ones
 * like [dev.getelements.conductor.admin.model.ExecuteJobRequest] fail with "no Creators, like default
 * constructor, exist". Registering a real `ObjectMapper` via this provider (see
 * [ConductorAdminApplication.getSingletons]) is sufficient to fix it — no Kotlin module or mixins
 * needed here, since the request DTOs already carry explicit annotations.
 */
@Provider
class ConductorAdminJacksonProvider : JacksonJsonProvider(ObjectMapper())
