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
