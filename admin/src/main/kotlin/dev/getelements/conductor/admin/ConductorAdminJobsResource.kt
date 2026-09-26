package dev.getelements.conductor.admin

import dev.getelements.conductor.JobAccessAction
import dev.getelements.conductor.JobAccessContext
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobVisibility
import dev.getelements.conductor.admin.model.ExecuteJobRequest
import dev.getelements.conductor.admin.model.StopJobRequest
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
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

private val TERMINAL_COMMAND_SUGGESTION = listOf("/bin/sh")

data class ProviderExecutionResult(
    val element: String,
    val executions: List<JobExecution>?,
    val error: String?,
    val jobSetName: String? = null,
    val jobSetDescription: String? = null
)

@Tag(name = "Conductor Admin")
@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class ConductorAdminJobsResource @Inject constructor(
    private val userService: UserService,
    private val sessionService: SessionService
) {

    private val logger = LoggerFactory.getLogger(ConductorAdminJobsResource::class.java)

    @GET
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "List running jobs across all providers",
        description = "Returns a point-in-time snapshot of executions from every deployed OrchestrationService provider. " +
            "Providers that fail to respond are included with a non-null error field. " +
            "SUPERUSER sessions see every execution. Otherwise a deployed JobAccessPolicy may grant " +
            "listing visibility, in which case rows are filtered serverside to the namespaces the " +
            "session may see; with no policy deployed (or none granting visibility) this requires " +
            "SUPERUSER level, as historically."
    )
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Execution list retrieved. Check the 'status' field: ok | partial | error.")
    @ApiResponse(responseCode = "403", description = "Not authenticated, or insufficient privilege level and no JobAccessPolicy grants visibility.")
    @ApiResponse(responseCode = "503", description = "No OrchestrationService providers are currently deployed.")
    fun listExecutions(@HeaderParam(Headers.SESSION_SECRET) sessionSecret: String?): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()

        // Policy gate — see JobAccessGate. SUPERUSER keeps the historical unfiltered listing;
        // everyone else lists only under a policy-granted visibility, filtered row by row.
        val visibility: JobVisibility =
            if (user.level == User.Level.SUPERUSER) JobVisibility.All
            else policyVisibility(sessionSecret, user)
                ?: return Response.status(Response.Status.FORBIDDEN).build()

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
                    val service = supplier.get()
                    ProviderExecutionResult(
                        element = name,
                        executions = service.listExecutions().filter { JobAccessGate.executionVisible(it, visibility) },
                        error = null,
                        jobSetName = service.jobSetName,
                        jobSetDescription = service.jobSetDescription
                    )
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
            "SUPERUSER sessions pass historically (absent a policy veto); otherwise a deployed " +
            "JobAccessPolicy may vote to allow the execute — e.g. for a job landing in a namespace " +
            "the session is authorized for."
    )
    @RequestBody(
        description = "Job execution request",
        required = true,
        content = [Content(schema = Schema(implementation = ExecuteJobRequest::class))]
    )
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiResponse(responseCode = "200", description = "Job submitted. Returns a JobExecution with id, status, and any initial endpoints.")
    @ApiResponse(responseCode = "403", description = "Not authenticated, vetoed by a JobAccessPolicy, or insufficient privilege level.")
    @ApiResponse(responseCode = "404", description = "Element or profile not found.")
    @ApiResponse(responseCode = "500", description = "The provider accepted the request but execution failed.")
    fun execute(@HeaderParam(Headers.SESSION_SECRET) sessionSecret: String?, request: ExecuteJobRequest): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()

        val service = ElementLookup.findByName(ConductorAdminJobsResource::class.java, request.element)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Element not found or does not expose OrchestrationService: ${request.element}"))
                .build()

        val profile = service.findAvailableProfile(request.profileId)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Profile not found: ${request.profileId}"))
                .build()

        // Policy gate — see JobAccessGate. A DENY from any policy vetoes outright (superusers
        // included); otherwise a single ALLOW suffices; with no vote either way the historical
        // SUPERUSER-only rule applies.
        if (!authorizedToExecute(sessionSecret, user, request.element, profile)) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }

        // A terminalJob profile implies tty (and a shell to run) when the caller didn't specify one —
        // covers both the admin UI's one-click "Start Terminal" button and any other caller that just
        // wants "the terminal this profile is meant for" without spelling out the details.
        val effectiveTty = request.tty ?: profile.terminalJob
        val effectiveCommand = request.command
            ?: (if (effectiveTty) TERMINAL_COMMAND_SUGGESTION else emptyList())

        val jobRequest = JobRequest(
            profile     = profile,
            args        = request.args ?: emptyList(),
            command     = effectiveCommand,
            environment = request.environment ?: emptyMap(),
            placement   = request.placement?.map { it.toPlacement() } ?: emptyList(),
            tty         = effectiveTty
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
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = AuthSchemes.SESSION_SECRET)
    @Operation(
        summary = "Stop a running job",
        description = "Stops the job identified by element and id. SUPERUSER sessions pass historically " +
            "(absent a policy veto); otherwise a deployed JobAccessPolicy may vote to allow the stop " +
            "for a job in a namespace the session is authorized for."
    )
    @RequestBody(
        description = "Stop request",
        required = true,
        content = [Content(schema = Schema(implementation = StopJobRequest::class))]
    )
    @ApiResponse(responseCode = "204", description = "Stop signal sent.")
    @ApiResponse(responseCode = "403", description = "Not authenticated, vetoed by a JobAccessPolicy, or insufficient privilege level.")
    @ApiResponse(responseCode = "404", description = "Element not found or does not expose OrchestrationService.")
    @ApiResponse(responseCode = "500", description = "Provider returned an error while stopping the job.")
    fun stop(@HeaderParam(Headers.SESSION_SECRET) sessionSecret: String?, request: StopJobRequest): Response {
        val user = userService.currentUser
            ?: return Response.status(Response.Status.FORBIDDEN).build()

        val service = ElementLookup.findByName(ConductorAdminJobsResource::class.java, request.element)
            ?: return Response.status(Response.Status.NOT_FOUND)
                .entity(mapOf("error" to "Element not found or does not expose OrchestrationService: ${request.element}"))
                .build()

        // Policy gate — see JobAccessGate, same aggregation as execute(). The job is resolved
        // across every provider first (the request's element name alone doesn't identify the
        // owning provider for the vote); a job no provider can resolve falls through to the
        // historical SUPERUSER-only rule — stop() itself will surface the 404-equivalent failure.
        if (!authorizedToStop(sessionSecret, user, request)) {
            return Response.status(Response.Status.FORBIDDEN).build()
        }

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

    // ── Policy gates ─────────────────────────────────────────────────────────────

    /**
     * Resolves the policy-granted listing visibility for the authenticated session, or `null`
     * when no [dev.getelements.conductor.JobAccessPolicy] is deployed (or none grants
     * visibility) — the caller's cue to apply the historical SUPERUSER-only rule.
     */
    private fun policyVisibility(sessionSecret: String?, user: User): JobVisibility? {
        val session: Session = sessionSecret?.let {
            try { sessionService.checkAndRefreshSessionIfNecessary(it) } catch (e: Exception) { null }
        } ?: return null
        return JobAccessGate.visibility(ConductorAdminJobsResource::class.java, session, user)
    }

    private fun authorizedToExecute(
        sessionSecret: String?,
        user: User,
        elementName: String,
        profile: dev.getelements.conductor.service.JobProfile,
    ): Boolean {
        val decision = when (val session = resolveSession(sessionSecret)) {
            null -> JobAccessGate.Decision.NoDecision
            else -> JobAccessGate.authorize(
                ConductorAdminJobsResource::class.java,
                JobAccessContext(
                    session = session,
                    user = user,
                    action = JobAccessAction.EXECUTE,
                    elementName = elementName,
                    profile = profile,
                    execution = null,
                    namespace = profile.namespace,
                )
            )
        }
        return decisionSatisfied(decision, user)
    }

    private fun authorizedToStop(sessionSecret: String?, user: User, request: StopJobRequest): Boolean {
        val lookup = ElementLookup.findByJobId(ConductorAdminJobsResource::class.java, request.id)
        val decision = when (val session = resolveSession(sessionSecret)) {
            null -> JobAccessGate.Decision.NoDecision
            else -> when (lookup) {
                null -> JobAccessGate.Decision.NoDecision
                else -> JobAccessGate.authorize(
                    ConductorAdminJobsResource::class.java,
                    JobAccessContext(
                        session = session,
                        user = user,
                        action = JobAccessAction.STOP,
                        elementName = lookup.elementName,
                        profile = null,
                        execution = lookup.execution,
                        namespace = lookup.execution.namespace,
                    )
                )
            }
        }
        return decisionSatisfied(decision, user)
    }

    private fun resolveSession(sessionSecret: String?): Session? = sessionSecret?.let {
        try { sessionService.checkAndRefreshSessionIfNecessary(it) } catch (e: Exception) { null }
    }

    /** Applies the aggregated [JobAccessGate.Decision]: a veto is final, an ALLOW passes, and no
     *  vote either way falls back to the historical SUPERUSER-only rule. */
    private fun decisionSatisfied(decision: JobAccessGate.Decision, user: User): Boolean = when (decision) {
        JobAccessGate.Decision.Denied -> false
        JobAccessGate.Decision.Allowed -> true
        JobAccessGate.Decision.NoDecision -> user.level == User.Level.SUPERUSER
    }

}