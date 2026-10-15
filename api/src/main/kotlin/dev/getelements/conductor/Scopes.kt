package dev.getelements.conductor

import dev.getelements.conductor.service.JobProfile

/**
 * Resolution of the provider-native scope a job row (profile, execution) belongs to — the grouping
 * key the admin dashboard's scope tree and the [JobAccessPolicy] visibility filter are both built
 * on. The concept is deliberately generic: Kubernetes scopes by its namespace, ECS by a reserved
 * tag, EdgeGap has no scope concept at all.
 *
 * Resolution order, shared by every consumer so the dashboard and the authorization layer can
 * never disagree:
 *
 *  1. The row's native scope field ([JobProfile.scope]/[JobExecution.scope]) — Kubernetes
 *     populates it with the namespace, which remains authoritative there.
 *  2. The reserved `namazu.conductor/scope` metadata key (either the Kubernetes
 *     `namazu.conductor/scope` annotation form or the ECS `namazu.conductor:scope` tag form,
 *     matched case-insensitively) — how ECS-scoped families declare their scope, read back from
 *     the live resource via the ordinary metadata channel.
 *  3. `null` — no scope concept or no declared scope. Consumers render such rows under a default
 *     node; [JobVisibility.All] is required to see them.
 *
 * The key is behavioural (see [Metadata.isBehavioral]): a caller override is refused, so scope is
 * declared on the task-definition family or `PodTemplate` by the infrastructure owner and cannot
 * be re-scoped per launch. Per-request *targeting* remains `JobScope`'s job (e.g. `ClusterScope`)
 * — this is about listing grouping, not placement.
 */
object Scopes {

    /**
     * The canonical reserved metadata key declaring a workload's scope. Both separator forms
     * (`namazu.conductor/scope`, `namazu.conductor:scope`) and any casing are matched.
     */
    const val METADATA_KEY = "namazu.conductor/scope"

    /**
     * The scope [profile] belongs to, per the resolution order in the class KDoc: the native
     * scope field first, else the reserved `scope` metadata key, else `null`.
     */
    fun of(profile: JobProfile): String? = profile.scope ?: fromMetadata(profile.metadata)

    /**
     * The scope [execution] belongs to, per the resolution order in the class KDoc: the native
     * scope field first, else the reserved `scope` metadata key, else `null`.
     */
    fun of(execution: JobExecution): String? = execution.scope ?: fromMetadata(execution.metadata)

    /**
     * The scope declared by a raw metadata map — the reserved `scope` key under either separator
     * form, case-insensitively, or `null` when absent/blank.
     */
    fun fromMetadata(metadata: Map<String, String>): String? =
        metadata.entries.firstOrNull { (k, _) ->
            val suffix = k.removePrefix(Metadata.RESERVED_PREFIX).trimStart('/', ':')
            k.startsWith(Metadata.RESERVED_PREFIX) && suffix.equals("scope", ignoreCase = true)
        }?.value?.trim()?.ifEmpty { null }

}
