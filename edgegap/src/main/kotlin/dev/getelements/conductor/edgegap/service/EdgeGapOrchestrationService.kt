package dev.getelements.conductor.edgegap.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.IpPlacement
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.LatitudeLongitudePlacement
import dev.getelements.conductor.edgegap.EdgeGapAttributes
import dev.getelements.conductor.edgegap.EdgeGapJobProfile
import dev.getelements.conductor.edgegap.model.EdgeGapAppVersionList
import dev.getelements.conductor.edgegap.model.EdgeGapAppsResponse
import dev.getelements.conductor.edgegap.model.EdgeGapDeployRequest
import dev.getelements.conductor.edgegap.model.EdgeGapDeployResponse
import dev.getelements.conductor.edgegap.model.EdgeGapDeploymentListResponse
import dev.getelements.conductor.edgegap.model.EdgeGapEnvVar
import dev.getelements.conductor.edgegap.model.EdgeGapExecutionDetails
import dev.getelements.conductor.edgegap.model.EdgeGapGeoIp
import dev.getelements.conductor.edgegap.model.EdgeGapStatusResponse
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.exception.StdioUnavailableException
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * [OrchestrationService] implementation backed by the [EdgeGap](https://edgegap.com) REST API v1.
 *
 * Profiles correspond to active EdgeGap app versions. A [JobRequest] is dispatched as an EdgeGap
 * deployment (`POST /v1/deploy`). The following [dev.getelements.conductor.JobPlacement] types are
 * supported:
 *
 * - [IpPlacement] — populates `ip_list` for geo-proximity host selection
 * - [LatitudeLongitudePlacement] — populates `geo_ip_list` for coordinate-based proximity matching
 * - [dev.getelements.conductor.RegionPlacement] — not supported by EdgeGap v1; silently ignored
 *
 * EdgeGap has no equivalent to a namespace or cluster — scoping is already fully determined by the
 * app/version identified by the [EdgeGapJobProfile], so any [dev.getelements.conductor.JobScope] on
 * the request is silently ignored.
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in
 * [EdgeGapAttributes].
 */
@Singleton
class EdgeGapOrchestrationService @Inject constructor(
    @Named(EdgeGapAttributes.API_KEY) private val apiKey: String,
    @Named(EdgeGapAttributes.BASE_URL) private val baseUrl: String,
    @Named(EdgeGapAttributes.POLL_INTERVAL) private val pollingIntervalMs: Long,
    @Named(EdgeGapAttributes.STDIO_BRIDGE_PORT) stdioBridgePort: String = "10080",
    @Named(EdgeGapAttributes.STDIO_BRIDGE_BASE_PATH) private val stdioBridgeBasePath: String = "",
    @Named(EdgeGapAttributes.API_KEY)
    private val client: Client,
    private val executor: ExecutorService
) : OrchestrationService {

    private val stdioBridgePortNum: Int = stdioBridgePort.toIntOrNull() ?: DEFAULT_STDIO_BRIDGE_PORT

    /**
     * Returns all active app versions across all EdgeGap applications as [EdgeGapJobProfile]s.
     * Pages through both the apps list and each app's version list exhaustively.
     */
    override fun getAvailableProfiles(): List<JobProfile> {
        val profiles = mutableListOf<EdgeGapJobProfile>()
        var page = 1

        do {

            val apps = target("/v1/apps")
                .queryParam("page", page)
                .queryParam("limit", PAGE_SIZE)
                .request(MediaType.APPLICATION_JSON)
                .header(AUTH_HEADER, authValue())
                .get(EdgeGapAppsResponse::class.java)

            for (app in apps.data ?: emptyList()) {
                if (app.isActive) profiles += getVersionsForApp(app.name)
            }

            page++

        } while (apps.pagination.hasNext)

        return profiles
    }

    private fun getVersionsForApp(appName: String): List<EdgeGapJobProfile> {
        val profiles = mutableListOf<EdgeGapJobProfile>()
        var page = 1

        do {
            val versions = target("/v1/app/{app_name}/versions")
                .resolveTemplate("app_name", appName)
                .queryParam("page", page)
                .queryParam("limit", PAGE_SIZE)
                .request(MediaType.APPLICATION_JSON)
                .header(AUTH_HEADER, authValue())
                .get(EdgeGapAppVersionList::class.java)

            (versions.data ?: emptyList())
                .filter { it.isActive }
                .mapTo(profiles) { EdgeGapJobProfile(appName, it.name) }

            page++
        } while (versions.pagination.hasNext)

        return profiles
    }

    /**
     * Polls `GET /v1/status/{request_id}` on a background thread until the deployment reaches
     * [status] (or [JobStatus.FAILED]). Each poll maps the EdgeGap lifecycle string to a
     * [JobStatus] and, once the target is reached, returns a [JobExecution] populated with
     * the current [JobEndpoint]s. [execution]'s `details` (including the [streamStdio] token
     * generated in [execute]) is carried forward unchanged, since EdgeGap's status API has no
     * notion of it — callers must be able to pass the result straight into [streamStdio].
     */
    override fun getFutureForStatus(
        execution: JobExecution,
        status: JobStatus
    ): Future<JobExecution> = CompletableFuture.supplyAsync({
        var result: JobExecution
        do {
            Thread.sleep(POLL_INTERVAL_MS)
            val statusResponse = fetchStatus(execution.id)
            result = JobExecution(
                id = execution.id,
                status = mapStatus(statusResponse.status),
                endpoints = mapEndpoints(statusResponse),
                details = execution.details
            )
        } while (result.status != status && result.status != JobStatus.FAILED)
        result
    }, executor)

    /**
     * Submits a deployment to EdgeGap and returns a [JobExecution] with status [JobStatus.PENDING].
     *
     * The [JobRequest.profile] must be an [EdgeGapJobProfile] obtained from [getAvailableProfiles].
     * [dev.getelements.conductor.RegionPlacement] entries in [JobRequest.placement] are ignored as
     * EdgeGap v1 does not support named-region placement.
     *
     * A fresh random token is generated per execution and injected as [STDIO_TOKEN_ENV_VAR], so a
     * `namazu-stdio-bridge` sidecar in the deployment's image (if present) requires it on every
     * connection. The token is carried on the returned [JobExecution]'s
     * [EdgeGapExecutionDetails.stdioToken] for [streamStdio] to present later — this requires no
     * manual configuration by the deployer; it's generated and threaded through automatically.
     *
     * @throws JobException if [JobRequest.profile] is not an [EdgeGapJobProfile]
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? EdgeGapJobProfile
            ?: throw JobException("JobProfile must be an ${EdgeGapJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

        val stdioToken = UUID.randomUUID().toString()

        val deployRequest = EdgeGapDeployRequest(
            appName = profile.appName,
            versionName = profile.versionName,
            ipList = request.placement
                .filterIsInstance<IpPlacement>()
                .map { it.ip },
            geoIpList = request.placement
                .filterIsInstance<LatitudeLongitudePlacement>()
                .map { EdgeGapGeoIp(latitude = it.latitude, longitude = it.longitude) },
            envVars = (request.environment + mapOf(STDIO_TOKEN_ENV_VAR to stdioToken))
                .map { (k, v) -> EdgeGapEnvVar(key = k, value = v) },
            command = (request.command + request.args).joinToString(" ").ifBlank { null },
            arguments = null
        )

        val response = target("/v1/deploy")
            .request(MediaType.APPLICATION_JSON)
            .header(AUTH_HEADER, authValue())
            .post(Entity.json(deployRequest), EdgeGapDeployResponse::class.java)

        return JobExecution(
            id = response.requestId,
            status = JobStatus.PENDING,
            details = EdgeGapExecutionDetails(
                appName = profile.appName,
                versionName = profile.versionName,
                stdioToken = stdioToken
            )
        )
    }

    override fun listExecutions(): List<JobExecution> {
        val executions = mutableListOf<JobExecution>()
        var page = 1
        do {
            val response = target("/v1/deployments")
                .queryParam("page", page)
                .queryParam("limit", PAGE_SIZE)
                .request(MediaType.APPLICATION_JSON)
                .header(AUTH_HEADER, authValue())
                .get(EdgeGapDeploymentListResponse::class.java)
            for (deployment in response.data) {
                executions += JobExecution(
                    id = deployment.requestId,
                    status = mapStatus(deployment.status),
                    endpoints = mapEndpoints(deployment.fqdn, deployment.publicIp, deployment.ports),
                    details = EdgeGapExecutionDetails(
                        appName = deployment.appName,
                        versionName = deployment.versionName,
                        fqdn = deployment.fqdn,
                        publicIp = deployment.publicIp
                    )
                )
            }
            page++
        } while (response.pagination.hasNext)
        return executions
    }

    override fun stop(execution: JobExecution) {
        target("/v1/stop/{request_id}")
            .resolveTemplate("request_id", execution.id)
            .request()
            .header(AUTH_HEADER, authValue())
            .delete()
    }

    /**
     * Opens a live, bidirectional stdio session for [execution] by connecting to a
     * `namazu-stdio-bridge` sidecar assumed to be listening on [stdioBridgePortNum] within the
     * deployment's container, at the same host resolved for [JobEndpoint]s. EdgeGap has no native
     * container stdio API, so this requires the app version's image to include the bridge (see
     * `stdio-bridge/README.md`) with its port declared in the app version's port mapping.
     *
     * Presents the token [execute] generated and injected into the deployment's environment as the
     * bridge's required `Authorization` bearer token — read from [execution]'s
     * [EdgeGapExecutionDetails.stdioToken], so [execution] must be the [JobExecution] originally
     * returned by [execute], or one derived from it via [getFutureForStatus]/[getStageForStatus]
     * (both carry `details` forward); an execution reconstructed from [listExecutions] instead has
     * no token available and can't authenticate.
     *
     * @throws StdioUnavailableException if [execution] has no [EdgeGapExecutionDetails.stdioToken],
     *   no reachable host yet, or the bridge can't be reached (not present in the image, port not
     *   mapped, wrong token, etc.)
     */
    override fun streamStdio(execution: JobExecution): JobStdio {
        val token = (execution.details as? EdgeGapExecutionDetails)?.stdioToken
            ?: throw StdioUnavailableException(
                "No stdio token available for execution '${execution.id}' — streamStdio requires " +
                    "the JobExecution originally returned by execute() (or one derived from it via " +
                    "getFutureForStatus/getStageForStatus), not one reconstructed from listExecutions()"
            )

        val status = fetchStatus(execution.id)
        val host = status.fqdn ?: status.publicIp
            ?: throw StdioUnavailableException("No reachable host for execution '${execution.id}' yet")

        return StdioBridgeClient.connect(host, stdioBridgePortNum, stdioBridgeBasePath, token)
    }

    private fun fetchStatus(requestId: String): EdgeGapStatusResponse =
        target("/v1/status/{request_id}")
            .resolveTemplate("request_id", requestId)
            .request(MediaType.APPLICATION_JSON)
            .header(AUTH_HEADER, authValue())
            .get(EdgeGapStatusResponse::class.java)

    private fun mapStatus(edgeGapStatus: String): JobStatus = when {
        edgeGapStatus.endsWith("INITIALIZING") || edgeGapStatus.endsWith("WAITING") -> JobStatus.PENDING
        edgeGapStatus.endsWith("RUNNING") -> JobStatus.RUNNING
        edgeGapStatus.endsWith("TERMINATED") || edgeGapStatus.endsWith("TERMINATING") -> JobStatus.COMPLETED
        else -> JobStatus.FAILED
    }

    private fun mapEndpoints(response: EdgeGapStatusResponse): List<JobEndpoint> =
        mapEndpoints(response.fqdn, response.publicIp, response.ports)

    private fun mapEndpoints(
        fqdn: String?,
        publicIp: String?,
        ports: Map<String, dev.getelements.conductor.edgegap.model.EdgeGapPort>
    ): List<JobEndpoint> {
        val host = fqdn ?: publicIp ?: return emptyList()
        return ports.values.map { port ->
            JobEndpoint(host = host, port = port.external, protocol = port.protocol)
        }
    }

    private fun target(path: String) = client.target(baseUrl).path(path)

    private fun authValue() = apiKey

    companion object {
        private const val AUTH_HEADER = "Authorization"
        private const val PAGE_SIZE = 100
        private const val POLL_INTERVAL_MS = 5_000L
        private const val DEFAULT_STDIO_BRIDGE_PORT = 10080

        /** Matches namazu-stdio-bridge's own required `NAMAZU_CONDUCTOR_STDIO_TOKEN` env var. */
        private const val STDIO_TOKEN_ENV_VAR = "NAMAZU_CONDUCTOR_STDIO_TOKEN"
    }

}