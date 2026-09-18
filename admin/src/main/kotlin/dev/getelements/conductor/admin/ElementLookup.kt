package dev.getelements.conductor.admin

import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.service.OrchestrationService
import dev.getelements.elements.sdk.ElementRegistrySupplier
import dev.getelements.elements.sdk.exception.SdkServiceNotFoundException

/**
 * Shared helpers for locating a deployed Element's [OrchestrationService] by name or by scanning
 * every deployed Element for a matching job id. Used by [ConductorAdminJobsResource] and the
 * terminal WebSocket layer, both of which need to resolve an [OrchestrationService] without a
 * static reference to which Element owns it.
 */
internal object ElementLookup {

    private const val SDK_SERVICE_ELEMENT_NAME = "dev.getelements.elements.sdk.service"

    /**
     * Resolves [serviceClass] from the Element named [elementName]'s own
     * [dev.getelements.elements.sdk.ServiceLocator], found by scanning the Element registry rather
     * than via [dev.getelements.elements.sdk.ElementSupplier.getElementLocal] — the latter resolves
     * via a `ServiceLoader` lookup keyed on the *calling class's own classloader*, which fails
     * identically for a caller instantiated outside Guice/HK2 (e.g. by the JSR-356 WebSocket
     * container) regardless of whether the target service lives in the caller's own Element's private
     * module or in an entirely different, core platform Element — it's a classloader problem, not a
     * visibility one (confirmed in production: `TerminalTicketStore`, bound and exposed from
     * [dev.getelements.conductor.admin.guice.ConductorAdminModule], was unreachable this way from
     * [ConductorAdminJobsResource], throwing `SdkServiceNotFoundException`). Scanning the registry for
     * the named Element and reading *its* `serviceLocator` directly mirrors
     * [forEachOrchestrationService]'s already-proven-working pattern for cross-Element lookups.
     */
    private fun <T> resolveService(callerClass: Class<*>, elementName: String, serviceClass: Class<T>): T {
        val registry = ElementRegistrySupplier.getElementLocal(callerClass).get()
        val element = registry.stream().toList().firstOrNull { it.elementRecord.definition().name() == elementName }
            ?: error("$elementName not found in the Element registry")
        return element.serviceLocator.getInstance(serviceClass)
    }

    /**
     * Resolves [serviceClass] from the core platform's `dev.getelements.elements.sdk.service` Element
     * — where core services like `SessionService`/`UserService` are actually exposed from (see
     * `SessionService`'s `@ElementPublic @ElementServiceExport`). The platform's own
     * `HttpServletAuthenticationFilter` (in `common-servlet`) is the sanctioned precedent for calling
     * `SessionService.checkAndRefreshSessionIfNecessary(secret)` outside the JAX-RS filter chain, but
     * it gets `SessionService` via plain HK2 `@Inject` — unavailable to callers with no DI context at
     * all (e.g. [dev.getelements.conductor.admin.ws.TerminalSessionHandler]), hence [resolveService].
     */
    fun <T> findCoreService(callerClass: Class<*>, serviceClass: Class<T>): T =
        resolveService(callerClass, SDK_SERVICE_ELEMENT_NAME, serviceClass)

    /**
     * Calls [action] once per deployed Element that exposes an [OrchestrationService], passing the
     * Element's name and its [OrchestrationService]. Elements that don't expose one (including the
     * `SdkServiceNotFoundException` visibility quirk documented on [ConductorAdminJobsResource]) are
     * silently skipped.
     */
    fun forEachOrchestrationService(callerClass: Class<*>, action: (elementName: String, service: OrchestrationService) -> Unit) {
        val registry = ElementRegistrySupplier.getElementLocal(callerClass).get()
        registry.stream().toList().forEach { element ->
            val name = element.elementRecord.definition().name()
            val service: OrchestrationService? = try {
                element.serviceLocator.findInstance(OrchestrationService::class.java).map { it.get() }.orElse(null)
            } catch (e: SdkServiceNotFoundException) {
                null
            }
            if (service != null) action(name, service)
        }
    }

    /**
     * Finds the [OrchestrationService] exposed by the Element named [elementName], or `null` if no
     * such Element is deployed or it doesn't expose one.
     */
    fun findByName(callerClass: Class<*>, elementName: String): OrchestrationService? {
        var found: OrchestrationService? = null
        forEachOrchestrationService(callerClass) { name, service ->
            if (name == elementName) found = service
        }
        return found
    }

    data class JobLookup(val elementName: String, val service: OrchestrationService, val execution: JobExecution)

    /**
     * Scans every deployed [OrchestrationService]-exposing Element's [OrchestrationService.listExecutions]
     * for an execution matching [jobId]. Used where only a job id is known (e.g. the terminal WebSocket
     * URL scheme carries no element name) — O(elements × executions) per call, acceptable at admin scale.
     */
    fun findByJobId(callerClass: Class<*>, jobId: String): JobLookup? {
        var found: JobLookup? = null
        forEachOrchestrationService(callerClass) { name, service ->
            if (found == null) {
                val execution = runCatching { service.listExecutions() }.getOrNull()?.firstOrNull { it.id == jobId }
                if (execution != null) found = JobLookup(name, service, execution)
            }
        }
        return found
    }

}
