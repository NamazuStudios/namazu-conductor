package dev.getelements.conductor.admin

import dev.getelements.conductor.JobVisibility
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
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

data class ProviderResult(
    val element: String,
    val providerType: String?,
    val profiles: List<Any>?,
    val error: String?,
    val jobSetName: String? = null,
    val jobSetDescription: String? = null
)

@Tag(name = "Conductor Admin")
@Path("/profiles")
@Produces(MediaType.APPLICATION_JSON)
class ConductorAdminResource @Inject constructor(
    private val userService: UserService,
    private val sessionService: SessionService
) {

    private val logger = LoggerFactory.getLogger(ConductorAdminResource::class.java)

    @GET
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "List job profiles across all providers",
        description = "Returns the available JobProfiles from every deployed OrchestrationService provider. " +
            "Providers that fail to respond are included with a non-null error field. " +
            "SUPERUSER sessions see every profile. Otherwise a deployed JobAccessPolicy may grant " +
            "listing visibility, in which case rows are filtered serverside to the namespaces the " +
            "session may see; with no policy deployed (or none granting visibility) this requires " +
            "SUPERUSER level, as historically."
    )
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Profile list retrieved. Check the 'status' field: ok | partial | error.")
    @ApiResponse(responseCode = "403", description = "Not authenticated, or insufficient privilege level and no JobAccessPolicy grants visibility.")
    @ApiResponse(responseCode = "503", description = "No OrchestrationService providers are currently deployed.",
        content = [Content(schema = Schema(example = """{"status":"error","message":"No OrchestrationService providers are deployed"}"""))])
    fun getProfiles(@HeaderParam(Headers.SESSION_SECRET) sessionSecret: String?): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()

        // Policy gate — see JobAccessGate. SUPERUSER keeps the historical unfiltered listing;
        // everyone else lists only under a policy-granted visibility, filtered row by row.
        val visibility: JobVisibility =
            if (user.level == User.Level.SUPERUSER) JobVisibility.All
            else policyVisibility(sessionSecret, user)
                ?: return Response.status(Response.Status.FORBIDDEN).build()

        val registry = ElementRegistrySupplier.getElementLocal(ConductorAdminResource::class.java).get()

        val providers = registry.stream().toList().mapNotNull { el ->
            val name = el.elementRecord.definition().name()
            // Workaround: FilteredServiceLocator.findInstance() throws SdkServiceNotFoundException
            // instead of returning Optional.empty() when the service is not visible to the calling
            // element. Should be fixed in the SDK. See
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
                    ProviderResult(
                        element = name,
                        providerType = profiles.firstOrNull()?.javaClass?.simpleName,
                        profiles = profiles.filter { JobAccessGate.profileVisible(it, visibility) },
                        error = null,
                        jobSetName = service.jobSetName,
                        jobSetDescription = service.jobSetDescription
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to retrieve profiles from element {}", name, e)
                    ProviderResult(element = name, providerType = null, profiles = null, error = e.message)
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
        return JobAccessGate.visibility(ConductorAdminResource::class.java, session, user)
    }
}