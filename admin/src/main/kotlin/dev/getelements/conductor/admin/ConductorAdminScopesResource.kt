package dev.getelements.conductor.admin

import dev.getelements.conductor.JobVisibility
import dev.getelements.conductor.Scopes
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.elements.sdk.ElementRegistrySupplier
import dev.getelements.elements.sdk.exception.SdkServiceNotFoundException
import dev.getelements.elements.sdk.jakarta.rs.AuthSchemes
import dev.getelements.elements.sdk.model.Headers
import dev.getelements.elements.sdk.model.session.Session
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.auth.SessionService
import dev.getelements.elements.sdk.service.user.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

/**
 * One provider's scope breakdown: the distinct resolved scopes its visible rows belong to, with
 * per-scope row counts. [scope] is `null` for rows with no resolved scope — consumers (the admin
 * dashboard's scope tree) group those under a default node.
 */
data class ScopeCount(
    val scope: String?,
    val profiles: Int,
    val executions: Int
)

data class ProviderScopeResult(
    val element: String,
    val providerType: String?,
    /** Null when the provider failed to respond — see [error]. */
    val scopes: List<ScopeCount>?,
    val error: String?,
    val jobSetName: String? = null,
    val jobSetDescription: String? = null
)

/**
 * Scope discovery for the admin dashboard's tree navigation (issue #52): a cheap per-element
 * breakdown of which scopes exist and how many visible rows each holds, so the panel can render
 * the tree from this call alone and only fetch rows for the scope the operator expands (via the
 * `scope` query parameter on `GET /profiles` and `GET /jobs`).
 *
 * Counts reflect the same visibility filtering as the row endpoints — scopes whose every row the
 * session may not see simply don't appear, mirroring [JobAccessGate]'s serverside filtering.
 */
@Tag(name = "Conductor Admin")
@Path("/scopes")
@Produces(MediaType.APPLICATION_JSON)
class ConductorAdminScopesResource @Inject constructor(
    private val userService: UserService,
    private val sessionService: SessionService
) {

    private val logger = LoggerFactory.getLogger(ConductorAdminScopesResource::class.java)

    @GET
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "List the scopes available across all providers",
        description = "Returns, for every deployed OrchestrationService provider, the distinct " +
            "scopes (Kubernetes namespaces, reserved namazu.conductor/scope tags, or null for " +
            "providers with no scope concept) its visible profiles and executions belong to, with " +
            "per-scope row counts. Visibility follows the same JobAccessPolicy rules as the row " +
            "endpoints; scopes whose every row is invisible are omitted. Providers that fail to " +
            "respond are included with a non-null error field and a null scopes list."
    )
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Scope list retrieved. Check the 'status' field: ok | partial | error.")
    @ApiResponse(responseCode = "403", description = "Not authenticated, or insufficient privilege level and no JobAccessPolicy grants visibility.")
    @ApiResponse(responseCode = "503", description = "No OrchestrationService providers are currently deployed.")
    fun listScopes(@HeaderParam(Headers.SESSION_SECRET) sessionSecret: String?): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()

        val visibility: JobVisibility =
            if (user.level == User.Level.SUPERUSER) JobVisibility.All
            else policyVisibility(sessionSecret, user)
                ?: return Response.status(Response.Status.FORBIDDEN).build()

        val registry = ElementRegistrySupplier.getElementLocal(ConductorAdminScopesResource::class.java).get()

        val providers = registry.stream().toList().mapNotNull { el ->
            val name = el.elementRecord.definition().name()
            val serviceOptional = try {
                el.serviceLocator.findInstance(OrchestrationService::class.java)
            } catch (e: SdkServiceNotFoundException) {
                logger.debug("Element {} does not expose OrchestrationService", name)
                return@mapNotNull null
            }
            serviceOptional.map { supplier ->
                try {
                    val service = supplier.get()
                    val profiles = service.getAvailableProfiles()
                        .filter { JobAccessGate.profileVisible(it, visibility) }
                        .groupBy { Scopes.of(it) }
                    val executions = service.listExecutions()
                        .filter { JobAccessGate.executionVisible(it, visibility) }
                        .groupBy { Scopes.of(it) }

                    val counts = (profiles.keys + executions.keys).distinct()
                        .map { scope ->
                            ScopeCount(
                                scope = scope,
                                profiles = profiles[scope]?.size ?: 0,
                                executions = executions[scope]?.size ?: 0
                            )
                        }
                        // Null scope (the "default" node) sorts last so named scopes read first.
                        .sortedWith(compareBy(nullsLast(), { it.scope }))

                    ProviderScopeResult(
                        element = name,
                        providerType = profiles.values.firstOrNull()?.firstOrNull()?.javaClass?.simpleName,
                        scopes = counts,
                        error = null,
                        jobSetName = service.jobSetName,
                        jobSetDescription = service.jobSetDescription
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to list scopes from element {}", name, e)
                    ProviderScopeResult(element = name, providerType = null, scopes = null, error = e.message)
                }
            }.orElse(null)
        }

        if (providers.isEmpty()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(mapOf("status" to "error", "message" to "No OrchestrationService providers are deployed"))
                .build()
        }

        val status = when {
            providers.all { it.error == null } -> "ok"
            providers.any { it.error == null } -> "partial"
            else -> "error"
        }

        return Response.ok(mapOf("status" to status, "providers" to providers)).build()
    }

    /**
     * Resolves the policy-granted listing visibility for the authenticated session, or `null`
     * when no [dev.getelements.conductor.JobAccessPolicy] is deployed (or none grants
     * visibility) — the caller's cue to apply the historical SUPERUSER-only rule.
     */
    private fun policyVisibility(sessionSecret: String?, user: User): JobVisibility? {
        val session: Session = sessionSecret?.let {
            try { sessionService.checkAndRefreshSessionIfNecessary(it) } catch (e: Exception) { null }
        } ?: return null
        return JobAccessGate.visibility(ConductorAdminScopesResource::class.java, session, user)
    }
}
