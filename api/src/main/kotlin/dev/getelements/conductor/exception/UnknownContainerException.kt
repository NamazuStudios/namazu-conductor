package dev.getelements.conductor.exception

/**
 * Thrown when a [dev.getelements.conductor.JobRequest.containerEnvironment] or
 * [dev.getelements.conductor.DaemonRequest.containerEnvironment] entry names a container that does
 * not exist in the target profile. Raised before any workload is created, so a rejected name can
 * never leave an orphaned job or daemon behind.
 */
class UnknownContainerException : JobException {

    constructor() : super()

    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    constructor(cause: Throwable?) : super(cause)

}
