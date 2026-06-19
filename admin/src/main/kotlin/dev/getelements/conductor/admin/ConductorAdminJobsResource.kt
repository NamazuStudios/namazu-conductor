package dev.getelements.conductor.admin

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.admin.model.ExecuteJobRequest
import dev.getelements.conductor.admin.model.StopJobRequest
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
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

data class ProviderExecutionResult(
    val element: String,
    val executions: List<JobExecution>?,
    val error: String?
)

@Tag(name = "Conductor Admin")
@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConductorAdminJobsResource @Inject constructor(private val userService: UserService) {

    private val logger = LoggerFactory.getLogger(ConductorAdminJobsResource::class.java)

    private fun requireSuperuser(): User? {
        val user = userService.currentUser ?: return null
        return if (user.level == User.Level.SUPERUSER) user else null
    }

    private fun resolveService(elementName: String): Pair<Response?, OrchestrationService> {
        val registry = ElementRegistrySupplier.getElementLocal(ConductorAdminJobsResource::class.java).get()
        val element = registry.stream().toList()
            .firstOrNull { it.elementRecord.definition().name() == elementName }
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Element not found: $elementName"))
                .build() to error("unreachable")

        val service: OrchestrationService? = try {
            element.serviceLocator.findInstance(OrchestrationService::class.java).map { it.get() }.orElse(null)
        } catch (e: SdkServiceNotFoundException) {
            null
        }

        return if (service == null) {
            Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Element $elementName does not expose OrchestrationService"))
                .build() to error("unreachable")
        } else {
            null to service
        }
    }

    @GET
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "List running jobs across all providers",
        description = "Returns a point-in-time snapshot of executions from every deployed OrchestrationService provider. " +
            "Providers that fail to respond are included with a non-null error field. " +
            "Requires SUPERUSER level."
    )
    @ApiResponse(responseCode = "200", description = "Execution list retrieved. Check the 'status' field: ok | partial | error.")
    @ApiResponse(responseCode = "403", description = "Not authenticated or insufficient privilege level.")
    @ApiResponse(responseCode = "503", description = "No OrchestrationService providers are currently deployed.")
    fun listExecutions(): Response {
        requireSuperuser() ?: return Response.status(Response.Status.FORBIDDEN).build()

        val registry = ElementRegistrySupplier.getElementLocal(ConductorAdminJobsResource::class.java).get()

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
                    ProviderExecutionResult(element = name, executions = supplier.get().listExecutions(), error = null)
                } catch (e: Exception) {
                    logger.warn("Failed to list executions from element {}", name, e)
                    ProviderExecutionResult(element = name, executions = null, error = e.message)
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
    @ApiResponse(responseCode = "404", description = "Element or profile not found.")
    @ApiResponse(responseCode = "500", description = "The provider accepted the request but execution failed.")
    fun execute(request: ExecuteJobRequest): Response {
        requireSuperuser() ?: return Response.status(Response.Status.FORBIDDEN).build()

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

    @POST
    @Path("/stop")
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "Stop a running job",
        description = "Stops the job identified by element and id. Requires SUPERUSER level."
    )
    @RequestBody(
        description = "Stop request",
        required = true,
        content = [Content(schema = Schema(implementation = StopJobRequest::class))]
    )
    @ApiResponse(responseCode = "204", description = "Stop signal sent.")
    @ApiResponse(responseCode = "403", description = "Not authenticated or insufficient privilege level.")
    @ApiResponse(responseCode = "404", description = "Element not found or does not expose OrchestrationService.")
    @ApiResponse(responseCode = "500", description = "Provider returned an error while stopping the job.")
    fun stop(request: StopJobRequest): Response {
        requireSuperuser() ?: return Response.status(Response.Status.FORBIDDEN).build()

        val registry = ElementRegistrySupplier.getElementLocal(ConductorAdminJobsResource::class.java).get()

        val element = registry.stream().toList()
            .firstOrNull { it.elementRecord.definition().name() == request.element }
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Element not found: ${request.element}"))
                .build()

        val service: OrchestrationService = try {
            element.serviceLocator.findInstance(OrchestrationService::class.java)
                .map { it.get() }
                .orElse(null)
        } catch (e: SdkServiceNotFoundException) {
            null
        } ?: return Response.status(Response.Status.NOT_FOUND)
            .entity(mapOf("error" to "Element ${request.element} does not expose OrchestrationService"))
            .build()

        return try {
            service.stop(dev.getelements.conductor.JobExecution(id = request.id, status = dev.getelements.conductor.JobStatus.RUNNING))
            Response.noContent().build()
        } catch (e: Exception) {
            logger.warn("Failed to stop job {} on element {}", request.id, request.element, e)
            Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(mapOf("error" to (e.message ?: "Stop failed")))
                .build()
        }
    }
}