package dev.getelements.conductor.exception

/**
 * Thrown when there is an exception running the job.
 */
open class JobException : RuntimeException {

    constructor() : super()

    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)

    constructor(cause: Throwable?) : super(cause)

}

