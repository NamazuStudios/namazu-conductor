package dev.getelements.conductor.admin

import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.admin.model.ExecuteJobRequest
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.elements.sdk.ElementRegistrySupplier
import dev.getelements.elements.sdk.exception.SdkServiceNotFoundException
import dev.getelements.elements.sdk.jakarta.rs.AuthSchemes
import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.user.UserService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConductorAdminJobsResource @Inject constructor(private val userService: UserService) {

    private val logger = LoggerFactory.getLogger(ConductorAdminJobsResource::class.java)

    @POST
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    fun execute(request: ExecuteJobRequest): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()
        if (user.level != User.Level.SUPERUSER)
            return Response.status(Response.Status.FORBIDDEN).build()

        val registry = ElementRegistrySupplier.getElementLocal(ConductorAdminJobsResource::class.java).get()

        val element = registry.stream().toList()
            .firstOrNull { it.elementRecord.definition().name() == request.element }
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Element not found: ${request.element}"))
                .build()

        // Workaround: FilteredServiceLocator.findInstance() throws SdkServiceNotFoundException
        // instead of returning Optional.empty() when the service is not visible to the calling
        // element. Should be fixed in the SDK.
        val service: OrchestrationService = try {
            element.serviceLocator.findInstance(OrchestrationService::class.java)
                .map { it.get() }
                .orElse(null)
        } catch (e: SdkServiceNotFoundException) {
            null
        } ?: return Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("error" to "Element ${request.element} does not expose OrchestrationService"))
            .build()

        val profile = service.findAvailableProfile(request.profileId)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Profile not found: ${request.profileId}"))
                .build()

        val jobRequest = JobRequest(
            profile     = profile,
            args        = request.args ?: emptyList(),
            command     = request.command ?: emptyList(),
            environment = request.environment ?: emptyMap(),
            placement   = request.placement?.map { it.toPlacement() } ?: emptyList()
        )

        return try {
            val execution = service.execute(jobRequest)
            Response.ok(execution).build()
        } catch (e: Exception) {
            logger.warn("Job execution failed for profile {} on element {}", request.profileId, request.element, e)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Execution failed")))
                .build()
        }
    }
}