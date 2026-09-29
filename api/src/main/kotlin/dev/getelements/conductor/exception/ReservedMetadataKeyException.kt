package dev.getelements.conductor.exception

import dev.getelements.conductor.Metadata

/**
 * Thrown when a caller-supplied [dev.getelements.conductor.JobRequest.metadata] (or
 * `DaemonRequest.metadata`) entry uses a behavioural `namazu.conductor` key — one that drives what
 * Conductor creates and how it manages it (e.g. `namazu.conductor/workload-kind`,
 * `namazu.conductor:jobSet`); see [Metadata.isBehavioral]. Thrown from
 * [Metadata.validate]/[Metadata.merge] before any workload is created. Cosmetic reserved keys
 * (`hidden`, `agent`, `link.{title}`, …) are overridable and never rejected.
 *
 * A caller mistake rather than a provider fault, so it is a [JobException] like every other
 * contract violation in this module — callers already catching `JobException` around `execute()`
 * need no new branch. Note that it is deliberately *not* an `IllegalArgumentException`: the
 * platform's existing `execute()` handlers treat that as an internal error, and a rejected
 * metadata key is a caller error that deserves to be reported as one.
 */
class ReservedMetadataKeyException : JobException {

    /**
     * The behavioural keys that were rejected, in iteration order.
     */
    val keys: List<String>

    constructor(keys: List<String>) : super(
        "Metadata key(s) ${keys.joinToString(prefix = "[", postfix = "]") { "'$it'" }} drive " +
            "Conductor's own behaviour and do not allow callers to override"
    ) {
        this.keys = keys
    }

}
