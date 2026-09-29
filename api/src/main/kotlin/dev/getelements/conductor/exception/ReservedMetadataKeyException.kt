package dev.getelements.conductor.exception

import dev.getelements.conductor.Metadata

/**
 * Thrown when a caller-supplied [dev.getelements.conductor.JobRequest.metadata] (or
 * `DaemonRequest.metadata`) entry uses a key carrying the [Metadata.RESERVED_PREFIX], which
 * Conductor reserves for its own semantics. Thrown from [Metadata.validate]/[Metadata.merge]
 * before any workload is created.
 *
 * A caller mistake rather than a provider fault, so it is a [JobException] like every other
 * contract violation in this module — callers already catching `JobException` around `execute()`
 * need no new branch. Note that it is deliberately *not* an `IllegalArgumentException`: the
 * platform's existing `execute()` handlers treat that as an internal error, and a rejected
 * metadata key is a caller error that deserves to be reported as one.
 */
class ReservedMetadataKeyException : JobException {

    /**
     * The reserved keys that were rejected, in iteration order.
     */
    val keys: List<String>

    constructor(keys: List<String>) : super(
        "Metadata key(s) ${keys.joinToString(prefix = "[", postfix = "]") { "'$it'" }} use the " +
            "reserved '${Metadata.RESERVED_PREFIX}' prefix, which Namazu Conductor reserves for " +
            "its own semantics and does not allow callers to override"
    ) {
        this.keys = keys
    }

}
