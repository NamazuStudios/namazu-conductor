package dev.getelements.conductor.admin

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import dev.getelements.conductor.service.SecretStore
import dev.getelements.conductor.service.StoredSecret
import dev.getelements.elements.sdk.jakarta.rs.AuthSchemes
import dev.getelements.elements.sdk.model.Headers
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.user.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

/**
 * REST surface over the deployed [SecretStore]s (issue #84): durable, namespaced secret storage
 * that jobs reference by name ([dev.getelements.conductor.SecretRef]) instead of receiving literal
 * values. Values are never echoed after write by [list]; [recall] is the one authorized read.
 *
 * Every operation requires a **SUPERUSER** session — secrets are control-plane material, and no
 * JobAccessPolicy-style delegation exists for them yet. Store implementations are addressed by
 * Element name (the same element that exposes the provider's `OrchestrationService`).
 */
data class ProviderSecretsResult(
    val element: String,
    val secrets: List<dev.getelements.conductor.service.SecretSummary>?,
    val error: String?
)

@Tag(name = "Conductor Admin")
@Path("/secrets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConductorAdminSecretsResource @Inject constructor(
    private val userService: UserService,
) {

    private val logger = LoggerFactory.getLogger(ConductorAdminSecretsResource::class.java)

    @GET
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "List stored secrets across all SecretStore providers",
        description = "Returns each deployed SecretStore's secrets by name and metadata only — " +
            "values are never echoed by listing. Requires SUPERUSER."
    )
    @ApiResponse(responseCode = "200", description = "Secret list retrieved. Check the 'status' field: ok | partial | error.")
    @ApiResponse(responseCode = "403", description = "Not authenticated or not SUPERUSER.")
    @ApiResponse(responseCode = "503", description = "No SecretStore providers are currently deployed.")
    fun list(): Response {
        requireSuperuser()?.let { return it }

        val stores = collectStores()

        if (stores.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(mapOf("status" to "error", "message" to "No SecretStore providers are deployed"))
                .build()
        }

        val status = when {
            stores.all { it.error == null } -> "ok"
            stores.any { it.error == null } -> "partial"
            else -> "error"
        }
        return Response.ok(mapOf("status" to status, "stores" to stores)).build()
    }    @GET
    @Path("/{element}/{name}")
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "Recall a stored secret (values included)",
        description = "Returns the secret's values and metadata. This is the one authorized read of " +
            "the secret's values. Requires SUPERUSER."
    )
    @ApiResponse(responseCode = "200", description = "The stored secret, with values.")
    @ApiResponse(responseCode = "403", description = "Not authenticated or not SUPERUSER.")
    @ApiResponse(responseCode = "404", description = "Element or secret not found.")
    fun recall(
        @PathParam("element") element: String,
        @PathParam("name") name: String
    ): Response {
        requireSuperuser()?.let { return it }
        val store = storeFor(element) ?: return storeUnavailable(element)
        return try {
            val secret = store.recall(name)
                ?: return Response.status(Response.Status.NOT_FOUND)
                    .entity(mapOf("error" to "Secret not found: $name"))
                    .build()
            Response.ok(secret).build()
        } catch (e: Exception) {
            storeErrorResponse(element, e)
        }
    }

    @PUT
    @Path("/{element}/{name}")
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "Create or replace a stored secret",
        description = "Stores values (and optional metadata) under the given name. Values are durable " +
            "after this call and are never returned by the listing. Requires SUPERUSER."
    )
    @RequestBody(
        description = "Secret values and metadata",
        required = true,
        content = [Content(schema = Schema(implementation = StoreSecretRequest::class))]
    )
    @ApiResponse(responseCode = "204", description = "Stored.")
    @ApiResponse(responseCode = "400", description = "Invalid secret name for the store's backend.")
    @ApiResponse(responseCode = "403", description = "Not authenticated or not SUPERUSER.")
    @ApiResponse(responseCode = "404", description = "Element not found or does not expose SecretStore.")
    fun store(
        @PathParam("element") element: String,
        @PathParam("name") name: String,
        request: StoreSecretRequest
    ): Response {
        requireSuperuser()?.let { return it }
        val store = storeFor(element) ?: return storeUnavailable(element)
        return try {
            store.store(name, request.values, request.metadata ?: emptyMap())
            Response.noContent().build()
        } catch (e: Exception) {
            logger.warn("Failed to store secret '{}' on element {}", name, element, e)
            Response.status(Response.Status.BAD_REQUEST)
                .entity(mapOf("error" to (e.message ?: "Store failed")))
                .build()
        }
    }

    @DELETE
    @Path("/{element}/{name}")
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "Delete a stored secret",
        description = "Deletes the named secret. Workloads already launched keep functioning " +
            "(injection resolves at launch time), but later launches referencing the secret fail. " +
            "Requires SUPERUSER."
    )
    @ApiResponse(responseCode = "204", description = "Deleted (or already absent).")
    @ApiResponse(responseCode = "403", description = "Not authenticated or not SUPERUSER.")
    @ApiResponse(responseCode = "404", description = "Element not found or does not expose SecretStore.")
    fun delete(
        @PathParam("element") element: String,
        @PathParam("name") name: String
    ): Response {
        requireSuperuser()?.let { return it }
        val store = storeFor(element) ?: return storeUnavailable(element)
        return try {
            store.delete(name)
            Response.noContent().build()
        } catch (e: Exception) {
            storeErrorResponse(element, e)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** A 403 Response when the caller isn't an authenticated SUPERUSER; null when authorized. */
    private fun requireSuperuser(): Response? {
        val user = userService.currentUser ?: return Response.status(Response.Status.FORBIDDEN).build()
        if (user.level != User.Level.SUPERUSER) return Response.status(Response.Status.FORBIDDEN).build()
        return null
    }

    /** Finds the [SecretStore] exposed by [element], or `null` — the caller's 404 cue. */
    private fun storeFor(element: String): SecretStore? {
        var found: SecretStore? = null
        ElementLookup.forEachSecretStore(ConductorAdminSecretsResource::class.java) { name, store ->
            if (name == element) found = store
        }
        return found
    }

    private fun collectStores(): List<ProviderSecretsResult> {
        val stores = mutableListOf<ProviderSecretsResult>()
        ElementLookup.forEachSecretStore(ConductorAdminSecretsResource::class.java) { name, store ->
            try {
                stores.add(ProviderSecretsResult(element = name, secrets = store.list(), error = null))
            } catch (e: Exception) {
                logger.warn("Failed to list secrets from element {}", name, e)
                stores.add(ProviderSecretsResult(element = name, secrets = null, error = e.message))
            }
        }
        return stores
    }

    private fun storeUnavailable(element: String): Response =
        Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("error" to "Element not found or does not expose SecretStore: $element"))
            .build()

    private fun storeErrorResponse(element: String, error: Exception): Response {
        logger.warn("Secret operation failed on element {}", element, error)
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(mapOf("error" to (error.message ?: "Secret operation failed")))
            .build()
    }

}

/**
 * The PUT body of [ConductorAdminSecretsResource.store]. A Kotlin data class in the admin
 * module's REST path needs an explicit [@JsonCreator][com.fasterxml.jackson.annotation.JsonCreator]
 * — the API classloader's Jackson has no Kotlin module (see the root AGENTS.md).
 */
data class StoreSecretRequest @JsonCreator constructor(
    /**
     * The secret's values, keyed by value name. A [dev.getelements.conductor.SecretRef] with no
     * explicit `key` requires exactly one entry.
     */
    @JsonProperty("values") val values: Map<String, String>,

    /**
     * Optional caller metadata (e.g. rotation date, owning team) — returned by the listing and
     * recall, never used by Conductor.
     */
    @JsonProperty("metadata") val metadata: Map<String, String>? = null,
)
