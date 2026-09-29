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
 * form and the ECS `namazu.conductor:tag` form — is reserved for Conductor's own semantics, but
 * reserved does not mean untouchable. The prefix holds two kinds of key:
 *
 *  - **Behavioural** (`namazu.conductor/workload-kind`, `namazu.conductor:jobSet`, replica tuning,
 *    …): these pick what Conductor *creates* and how it manages it. A caller may not override
 *    them — asking the workload to be one thing while Conductor manages it as another is a
 *    caller error; see [validate] and [ReservedMetadataKeyException]. Declaring them on a
 *    profile remains fine: a template is trusted to set Conductor's own keys, since that is
 *    exactly what it is for.
 *
 *  - **Cosmetic** (`hidden`, `agent`, `link.{title}`, `description`, `display-name`, and anything
 *    else not in the behavioural set): hints consumed by the admin UI and free to override per
 *    run — a caller may launch the same template visibly, hidden, or as an agent, or attach
 *    different links. Keys *not yet* assigned meaning by any Conductor release are also
 *    overridable, at the caller's own risk: a future release may make a key behavioural, and an
 *    override of it would then start being refused.
 *
 * Merging is overlay-only: a caller can change any declared value but cannot *remove* a declared
 * key.
 */
object Metadata {

    /**
     * The key prefix reserved for Conductor's own semantics, common to both the Kubernetes
     * `namazu.conductor/` annotation form and the ECS `namazu.conductor:` tag form. Only the
     * *behavioural* keys under it (see [isBehavioral]) are protected from caller overrides.
     */
    const val RESERVED_PREFIX = "namazu.conductor"

    /**
     * The prefix-stripped, separator-insensitive key suffixes that drive Conductor's behaviour —
     * the union of every key any shipped provider interprets as more than presentation. A caller
     * override carrying any of these (compared case-insensitively, so a mistyped casing cannot
     * smuggle a behavioural key past the guard) is refused; everything else under
     * [RESERVED_PREFIX] is cosmetic and overridable.
     */
    private val BEHAVIORAL_KEY_SUFFIXES = setOf(
        // kubernetes — workload-kind selection, scoping, services, job tuning
        "workload-kind", "expose-ports", "service-type", "terminal-job", "helm-release",
        "ttl-seconds-after-finished", "backoff-limit", "active-deadline-seconds",
        "completions", "parallelism", "session-secret-env", "enable-session-secret",
        // kubernetes — daemon tuning
        "replicas", "min-replicas", "max-replicas", "target-cpu-utilization-percentage",
        // kubernetes — synthesized on created workloads, never caller-owned
        "default-container-exec",
        // ecs — workload-kind selection, scoping, launch shape
        "workloadkind", "jobset", "job-set", "launchtype", "assignpublicip",
        // ecs — daemon tuning
        "desiredcount", "mincount", "maxcount",
    )

    /**
     * True when [key] carries the [RESERVED_PREFIX] reserved for Conductor's own semantics.
     */
    fun isReserved(key: String): Boolean = key.startsWith(RESERVED_PREFIX)

    /**
     * True when [key] is a behavioural Conductor key — a [isReserved] key whose suffix (after the
     * `/` or `:` separator, compared case-insensitively) is one of the keys Conductor gives
     * behaviour to; see [BEHAVIORAL_KEY_SUFFIXES]. Cosmetic reserved keys (`hidden`, `agent`,
     * `link.{title}`, `description`, `display-name`, …) and unknown reserved keys are not
     * behavioural and are free to override.
     */
    fun isBehavioral(key: String): Boolean =
        isReserved(key) &&
            key.removePrefix(RESERVED_PREFIX).trimStart('/', ':').lowercase() in BEHAVIORAL_KEY_SUFFIXES

    /**
     * Throws [ReservedMetadataKeyException] if [overrides] contains any [isBehavioral] key.
     *
     * Only caller-supplied metadata is validated, and only its behavioural keys are rejected.
     * Infrastructure-declared metadata is exempt and reported in full, reserved keys included.
     *
     * @throws ReservedMetadataKeyException if any key is behavioural.
     */
    fun validate(overrides: Map<String, String>) {
        val behavioral = overrides.keys.filter(::isBehavioral)
        if (behavioral.isNotEmpty()) throw ReservedMetadataKeyException(behavioral)
    }

    /**
     * The full metadata for a run: [base] (the profile's or daemon's infrastructure-declared set)
     * with [overrides] applied on top, [overrides] winning any key collision.
     *
     * This is the single implementation of the override rule, shared by every provider so they
     * cannot drift apart, and by the admin REST layer so a rejected override surfaces as a client
     * error rather than a provider fault.
     *
     * @throws ReservedMetadataKeyException if [overrides] contains any [isBehavioral] key.
     */
    fun merge(base: Map<String, String>, overrides: Map<String, String>): Map<String, String> {
        validate(overrides)
        return base + overrides
    }

}
