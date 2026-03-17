package dev.getelements.conductor.edgegap.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.IpPlacement
import dev.getelements.conductor.JobEndpoint
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.LatitudeLongitudePlacement
import dev.getelements.conductor.edgegap.EdgeGapAttributes
import dev.getelements.conductor.edgegap.EdgeGapJobProfile
import dev.getelements.conductor.edgegap.model.EdgeGapAppVersionList
import dev.getelements.conductor.edgegap.model.EdgeGapAppsResponse
import dev.getelements.conductor.edgegap.model.EdgeGapDeployRequest
import dev.getelements.conductor.edgegap.model.EdgeGapDeployResponse
import dev.getelements.conductor.edgegap.model.EdgeGapEnvVar
import dev.getelements.conductor.edgegap.model.EdgeGapGeoIp
import dev.getelements.conductor.edgegap.model.EdgeGapStatusResponse
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
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
 * Configuration is provided by the Elements SDK via the attribute keys declared in
 * [EdgeGapAttributes].
 */
@Singleton
class EdgeGapOrchestrationService @Inject constructor(
    @Named(EdgeGapAttributes.API_KEY) private val apiKey: String,
    @Named(EdgeGapAttributes.BASE_URL) private val baseUrl: String,
    private val client: Client,
    private val executor: ExecutorService
) : OrchestrationService {

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

            for (app in apps.data) {
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

            versions.data
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
     * the current [JobEndpoint]s.
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
                endpoints = mapEndpoints(statusResponse)
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
     * @throws JobException if [JobRequest.profile] is not an [EdgeGapJobProfile]
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? EdgeGapJobProfile
            ?: throw JobException("JobProfile must be an ${EdgeGapJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

        val deployRequest = EdgeGapDeployRequest(
            appName = profile.appName,
            versionName = profile.versionName,
            ipList = request.placement
                .filterIsInstance<IpPlacement>()
                .map { it.ip },
            geoIpList = request.placement
                .filterIsInstance<LatitudeLongitudePlacement>()
                .map { EdgeGapGeoIp(latitude = it.latitude, longitude = it.longitude) },
            envVars = request.environment
                .map { (k, v) -> EdgeGapEnvVar(key = k, value = v) },
            command = request.command.joinToString(" ").ifBlank { null },
            arguments = request.args.joinToString(" ").ifBlank { null }
        )

        val response = target("/v1/deploy")
            .request(MediaType.APPLICATION_JSON)
            .header(AUTH_HEADER, authValue())
            .post(Entity.json(deployRequest), EdgeGapDeployResponse::class.java)

        return JobExecution(id = response.requestId, status = JobStatus.PENDING)
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

    private fun mapEndpoints(response: EdgeGapStatusResponse): List<JobEndpoint> {
        val host = response.fqdn ?: response.publicIp ?: return emptyList()
        return response.ports.values.map { port ->
            JobEndpoint(host = host, port = port.external, protocol = port.protocol)
        }
    }

    private fun target(path: String) = client.target(baseUrl).path(path)

    private fun authValue() = "token $apiKey"

    companion object {
        private const val AUTH_HEADER = "Authorization"
        private const val PAGE_SIZE = 100
        private const val POLL_INTERVAL_MS = 5_000L
    }

}