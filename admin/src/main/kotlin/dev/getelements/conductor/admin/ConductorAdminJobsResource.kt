package dev.getelements.conductor.admin

import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.admin.model.ExecuteJobRequest
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.elements.sdk.ElementRegistrySupplier
import dev.getelements.elements.sdk.exception.SdkServiceNotFoundException
import dev.getelements.elements.sdk.jakarta.rs.AuthSchemes
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
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

@Tag(name = "Conductor Admin")
@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConductorAdminJobsResource @Inject constructor(private val userService: UserService) {

    private val logger = LoggerFactory.getLogger(ConductorAdminJobsResource::class.java)

    @POST
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "Execute a job",
        description = "Dispatches a job to the specified provider element using the named profile. " +
            "Returns a JobExecution immediately — the job will typically be PENDING at this point. " +
            "Requires SUPERUSER level."
    )
    @RequestBody(
        description = "Job execution request",
        required = true,
        content = [Content(schema = Schema(implementation = ExecuteJobRequest::class))]
    )
    @ApiResponse(responseCode = "200", description = "Job submitted. Returns a JobExecution with id, status, and any initial endpoints.")
    @ApiResponse(responseCode = "403", description = "Not authenticated or insufficient privilege level.")
    @ApiResponse(responseCode = "404", description = "Element or profile not found.",
        content = [Content(schema = Schema(example = """{"error":"Profile not found: my-profile"}"""))])
    @ApiResponse(responseCode = "500", description = "The provider accepted the request but execution failed.",
        content = [Content(schema = Schema(example = """{"error":"Connection refused"}"""))])
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