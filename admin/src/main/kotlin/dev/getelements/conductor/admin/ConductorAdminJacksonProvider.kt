package dev.getelements.conductor.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider

/**
 * Without an explicitly registered Jackson provider, the platform's default JSON handling for this
 * Element serializes responses fine (plain getters, no creator needed) but cannot deserialize request
 * bodies into Kotlin data classes at all — even fully `@JsonCreator`/`@JsonProperty`-annotated ones
 * like [dev.getelements.conductor.admin.model.ExecuteJobRequest] fail with "no Creators, like default
 * constructor, exist". Registering a real `ObjectMapper` via this provider (see
 * [ConductorAdminApplication.getSingletons]) is sufficient to fix it — no Kotlin module or mixins
 * needed here, since the request DTOs already carry explicit annotations.
 *
 * `@Consumes`/`@Produces` must be explicit: without them a MessageBodyReader/Writer implicitly
 * matches the wildcard media type, which loses JAX-RS's most-specific-media-type provider selection
 * to any other provider that explicitly declares `application/json` — leaving this instance
 * constructed (via getSingletons()) but never actually invoked to read a request body.
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class ConductorAdminJacksonProvider : JacksonJsonProvider(ObjectMapper())
