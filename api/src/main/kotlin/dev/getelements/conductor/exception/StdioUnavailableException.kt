package dev.getelements.conductor.exception

/**
 * Thrown when a provider supports [dev.getelements.conductor.service.OrchestrationService.streamStdio]
 * in principle, but the process for a specific execution isn't reachable yet (job not started, process
 * no longer running, bridge not present, etc.). Providers with no stdio support at all throw
 * [UnsupportedOperationException] instead.
 */
class StdioUnavailableException : JobException {

    constructor() : super()

    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    constructor(cause: Throwable?) : super(cause)

}