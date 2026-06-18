package dev.getelements.conductor.admin

import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.elements.sdk.ElementRegistrySupplier
import dev.getelements.elements.sdk.exception.SdkServiceNotFoundException
import dev.getelements.elements.sdk.jakarta.rs.AuthSchemes
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.user.UserService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

data class ProviderResult(
    val element: String,
    val providerType: String?,
    val profiles: List<Any>?,
    val error: String?
)

@Path("/profiles")
@Produces(MediaType.APPLICATION_JSON)
class ConductorAdminResource @Inject constructor(private val userService: UserService) {

    private val logger = LoggerFactory.getLogger(ConductorAdminResource::class.java)

    @GET
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    fun getProfiles(): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()

        if (user.level != User.Level.SUPERUSER) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }

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
                    val profiles = supplier.get().getAvailableProfiles()
                    ProviderResult(
                        element = name,
                        providerType = profiles.firstOrNull()?.javaClass?.simpleName,
                        profiles = profiles,
                        error = null
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
}