package dev.getelements.conductor.multiplay.service

import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.inject.name.Named
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobRequest
import dev.getelements.conductor.JobStatus
import dev.getelements.conductor.RegionPlacement
import dev.getelements.conductor.exception.JobException
import dev.getelements.conductor.multiplay.MultiplayAttributes
import dev.getelements.conductor.multiplay.MultiplayJobProfile
import dev.getelements.conductor.multiplay.model.MultiplayAllocationRequest
import dev.getelements.conductor.multiplay.model.MultiplayAllocationResponse
import dev.getelements.conductor.multiplay.model.MultiplayBuildConfiguration
import dev.getelements.conductor.multiplay.model.MultiplayBuildConfigurationPage
import dev.getelements.conductor.multiplay.model.MultiplayFleet
import dev.getelements.conductor.multiplay.model.MultiplayFleetPage
import dev.getelements.conductor.multiplay.model.MultiplayTokenResponse
import dev.getelements.conductor.service.JobProfile
import dev.getelements.conductor.service.OrchestrationService
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.core.MediaType
import java.util.Base64

/**
 * [OrchestrationService] implementation backed by Unity Multiplay via its REST API.
 *
 * Profiles correspond to build configurations within fleets. The [getAvailableProfiles] method
 * enumerates all fleets, then resolves their associated build configuration names from the
 * build-configurations API, producing one [MultiplayJobProfile] per (fleet, build configuration)
 * pair.
 *
 * Authentication uses the Unity service-account token-exchange flow: the keyId/keySecret pair is
 * exchanged for a short-lived Bearer token via `POST /auth/v1/token-exchange`. Tokens are cached
 * for [TOKEN_TTL_MS] milliseconds and refreshed transparently before expiry.
 *
 * [RegionPlacement] hints are respected — the first entry is forwarded as `regionId` in the
 * allocation request. All other placement types are silently ignored.
 *
 * Configuration is provided by the Elements SDK via the attribute keys declared in
 * [MultiplayAttributes].
 */
@Singleton
class MultiplayOrchestrationService @Inject constructor(
    @Named(MultiplayAttributes.KEY_ID) private val keyId: String,
    @Named(MultiplayAttributes.KEY_SECRET) private val keySecret: String,
    @Named(MultiplayAttributes.PROJECT_ID) private val projectId: String,
    @Named(MultiplayAttributes.ENVIRONMENT_ID) private val environmentId: String,
    private val client: Client
) : OrchestrationService {

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiry: Long = 0L

    /**
     * Returns one [MultiplayJobProfile] per active (fleet, build configuration) pair.
     *
     * Fleets are paginated from the fleets API; build configuration names are resolved from
     * the build-configurations API. Only configurations referenced by at least one fleet are
     * included.
     */
    override fun getAvailableProfiles(): List<JobProfile> {
        val fleets = listAllFleets()
        if (fleets.isEmpty()) return emptyList()

        val referencedIds = fleets.flatMap { it.buildConfigurations }.toSet()
        val buildConfigNames = listAllBuildConfigurations()
            .filter { it.id in referencedIds }
            .associate { it.id to it.name }

        return fleets.flatMap { fleet ->
            fleet.buildConfigurations
                .filter { it in buildConfigNames }
                .map { buildConfigId ->
                    MultiplayJobProfile(
                        fleetId = fleet.fleetId,
                        buildConfigurationId = buildConfigId,
                        name = buildConfigNames.getValue(buildConfigId)
                    )
                }
        }
    }

    private fun listAllFleets(): List<MultiplayFleet> {
        val results = mutableListOf<MultiplayFleet>()
        var offset = 0
        var total: Int

        do {
            val page = servicesTarget("/multiplay/fleets/v1/projects/{project}/environments/{env}/fleets")
                .resolveTemplate("project", projectId)
                .resolveTemplate("env", environmentId)
                .queryParam("limit", PAGE_SIZE)
                .queryParam("offset", offset)
                .request(MediaType.APPLICATION_JSON)
                .header(AUTH_HEADER, authBearer())
                .get(MultiplayFleetPage::class.java)

            results += page.results
            total = page.total
            offset += page.results.size
        } while (offset < total)

        return results
    }

    private fun listAllBuildConfigurations(): List<MultiplayBuildConfiguration> {
        val results = mutableListOf<MultiplayBuildConfiguration>()
        var offset = 0
        var total: Int

        do {
            val page = servicesTarget("/multiplay/build-configurations/v1/projects/{project}/environments/{env}/build-configurations")
                .resolveTemplate("project", projectId)
                .resolveTemplate("env", environmentId)
                .queryParam("limit", PAGE_SIZE)
                .queryParam("offset", offset)
                .request(MediaType.APPLICATION_JSON)
                .header(AUTH_HEADER, authBearer())
                .get(MultiplayBuildConfigurationPage::class.java)

            results += page.results
            total = page.total
            offset += page.results.size
        } while (offset < total)

        return results
    }

    /**
     * Allocates a Multiplay server and returns a [JobExecution] with status [JobStatus.PENDING].
     *
     * The first [RegionPlacement] in [JobRequest.placement], if present, is forwarded as the
     * `regionId` hint. All other placement types and any command/args are silently ignored as
     * Multiplay does not support runtime command overrides via its allocation API.
     *
     * @throws JobException if [JobRequest.profile] is not a [MultiplayJobProfile]
     */
    override fun execute(request: JobRequest): JobExecution {
        val profile = request.profile as? MultiplayJobProfile
            ?: throw JobException("JobProfile must be a ${MultiplayJobProfile::class.simpleName}; got ${request.profile::class.simpleName}")

        val regionId = request.placement
            .filterIsInstance<RegionPlacement>()
            .firstOrNull()
            ?.id

        val allocationRequest = MultiplayAllocationRequest(
            buildConfigurationId = profile.buildConfigurationId,
            regionId = regionId
        )

        val response = allocationTarget("/v1/allocations/projects/{project}/environments/{env}/fleets/{fleet}/allocations")
            .resolveTemplate("project", projectId)
            .resolveTemplate("env", environmentId)
            .resolveTemplate("fleet", profile.fleetId)
            .request(MediaType.APPLICATION_JSON)
            .header(AUTH_HEADER, authBearer())
            .post(Entity.json(allocationRequest), MultiplayAllocationResponse::class.java)

        return JobExecution(id = response.allocationId, status = JobStatus.PENDING)
    }

    private fun authBearer(): String {
        if (System.currentTimeMillis() < tokenExpiry) return "Bearer ${cachedToken!!}"
        return synchronized(this) {
            if (System.currentTimeMillis() < tokenExpiry) return "Bearer ${cachedToken!!}"
            val credentials = Base64.getEncoder().encodeToString("$keyId:$keySecret".toByteArray())
            val response = servicesTarget("/auth/v1/token-exchange")
                .request(MediaType.APPLICATION_JSON)
                .header(AUTH_HEADER, "Basic $credentials")
                .post(Entity.json("{}"), MultiplayTokenResponse::class.java)
            cachedToken = response.accessToken
            tokenExpiry = System.currentTimeMillis() + TOKEN_TTL_MS
            "Bearer ${response.accessToken}"
        }
    }

    private fun servicesTarget(path: String) = client.target(SERVICES_BASE_URL).path(path)

    private fun allocationTarget(path: String) = client.target(ALLOCATION_BASE_URL).path(path)

    companion object {
        private const val AUTH_HEADER = "Authorization"
        private const val SERVICES_BASE_URL = "https://services.api.unity.com"
        private const val ALLOCATION_BASE_URL = "https://multiplay.services.api.unity.com"
        private const val PAGE_SIZE = 100
        private const val TOKEN_TTL_MS = 55 * 60 * 1000L // 55 minutes; tokens expire after 1 hour
    }

}