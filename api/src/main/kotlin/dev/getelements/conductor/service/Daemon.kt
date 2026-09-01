package dev.getelements.conductor.service

/**
 * Describes a pre-configured daemon template available on a [DaemonOrchestrationService]. Each
 * [DaemonOrchestrationService] exposes its own set of daemons via
 * [DaemonOrchestrationService.getAvailableDaemons]; the contents of a daemon (container image,
 * resource limits, etc.) are managed by the orchestrator implementation and are opaque to callers.
 * A daemon is referenced by [dev.getelements.conductor.DaemonRequest] to select which template the
 * submitted deployment should use.
 */
interface Daemon {

    /**
     * The unique identifier of this [Daemon] within its [DaemonOrchestrationService].
     */
    val id: String;

}
