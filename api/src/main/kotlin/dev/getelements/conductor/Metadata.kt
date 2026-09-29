package dev.getelements.conductor

import dev.getelements.conductor.exception.ReservedMetadataKeyException

/**
 * Free-form, provider-agnostic presentation metadata attached to a job or daemon.
 *
 * Conductor is a presentation and orchestration layer, not a resource management layer: it
 * carries these keys and values verbatim and assigns **no meaning to any of them** — no
 * well-known key set, no reserved names, no interpretation. Consumers that care about a key
 * (a dashboard rendering a preview URL, a `JobAccessPolicy` gating on a `tier`, some other
 * Element entirely) decide for themselves what a key means.
 *
 * The set has two halves:
 *
 *  - **Infrastructure-declared**, read off the provider-native resource the profile is derived
 *    from (the whole `PodTemplate` annotation map on Kubernetes, the whole task-definition tag
 *    map on ECS). Report the **full** set under full provider-native keys — no stripping, no
 *    prefix removal, no deduplication, no filtering. This is deliberate: a `namazu.conductor/<key>`
 *    key that Conductor also surfaces as a typed field (e.g. `namazu.conductor/description` →
 *    [dev.getelements.conductor.service.JobProfile.description]) appears in **both** places, so
 *    nothing is hidden from a caller that reads the raw map.
 *
 *  - **Caller-supplied**, carried on
 *    [dev.getelements.conductor.JobRequest.metadata]/[dev.getelements.conductor.DaemonRequest.metadata]
 *    and merged over the infrastructure-declared half, so a run can override any declared value.
 *
 * The `namazu.conductor` prefix — spanning both the Kubernetes `namazu.conductor/annotation`
 * form and the ECS `namazu.conductor:tag` form — is **reserved for Conductor's own semantics**,
 * and a caller may not override it; see [validate] and
 * [ReservedMetadataKeyException]. The prefix is reserved because Conductor gives those keys
 * behaviour: `namazu.conductor/workload-kind` picks the workload primitive, and so on. A caller
 * overriding one of those would be asking the workload to be one thing while Conductor manages it
 * as another. Declaring *new* keys under the prefix is likewise discouraged for the same reason —
 * a future Conductor release may assign them meaning. Use a prefix you own.
 *
 * Merging is overlay-only: a caller can change any declared value but cannot *remove* a declared
 * key.
 */
object Metadata {

    /**
     * The key prefix reserved for Conductor's own semantics, common to both the Kubernetes
     * `namazu.conductor/` annotation form and the ECS `namazu.conductor:` tag form. Callers may
     * neither override nor newly declare keys under it — see the type KDoc.
     */
    const val RESERVED_PREFIX = "namazu.conductor"

    /**
     * True when [key] is reserved for Conductor's own semantics, i.e. carries the
     * [RESERVED_PREFIX].
     */
    fun isReserved(key: String): Boolean = key.startsWith(RESERVED_PREFIX)

    /**
     * Throws [ReservedMetadataKeyException] if [overrides] contains any [isReserved] key.
     *
     * Only caller-supplied metadata is validated. Infrastructure-declared metadata is exempt and
     * reported in full, reserved keys included — a template is trusted to set Conductor's own
     * annotations, since that is exactly what it is for.
     *
     * @throws ReservedMetadataKeyException if any key is reserved.
     */
    fun validate(overrides: Map<String, String>) {
        val reserved = overrides.keys.filter(::isReserved)
        if (reserved.isNotEmpty()) throw ReservedMetadataKeyException(reserved)
    }

    /**
     * The full metadata for a run: [base] (the profile's or daemon's infrastructure-declared set)
     * with [overrides] applied on top, [overrides] winning any key collision.
     *
     * This is the single implementation of the override rule, shared by every provider so they
     * cannot drift apart, and by the admin REST layer so a rejected override surfaces as a client
     * error rather than a provider fault.
     *
     * @throws ReservedMetadataKeyException if [overrides] contains any [isReserved] key.
     */
    fun merge(base: Map<String, String>, overrides: Map<String, String>): Map<String, String> {
        validate(overrides)
        return base + overrides
    }

}
