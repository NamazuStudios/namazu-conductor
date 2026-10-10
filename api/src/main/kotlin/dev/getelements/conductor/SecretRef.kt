package dev.getelements.conductor

/**
 * A declarative reference to a [dev.getelements.conductor.service.StoredSecret]: rather than
 * receiving the secret's value literally in [JobRequest.environment] — where it would be visible
 * in annotations, logs, and the workload's own spec — a launch references a secret by name and the
 * orchestrator injects it at launch time.
 *
 * On providers with a [dev.getelements.conductor.service.SecretStore] the referenced secret is
 * resolved and made available to the workload without its value ever appearing in the workload's
 * spec (e.g. a Kubernetes `valueFrom.secretKeyRef` env source resolved by the kubelet at schedule
 * time). A provider without secret support rejects a launch carrying any [SecretRef] outright —
 * never a silent drop.
 */
data class SecretRef(

    /**
     * The name of the stored secret, as accepted by
     * [dev.getelements.conductor.service.SecretStore.recall]. Naming a secret that does not exist
     * fails the launch outright, before anything is created.
     */
    val name: String,

    /**
     * The environment variable name the secret's value is injected under. Must not collide with
     * any other env source on the same container (profile-declared env, [JobRequest.environment],
     * or [JobRequest.containerEnvironment]) — a collision fails the launch before anything is
     * created.
     */
    val envKey: String,

    /**
     * The container the secret is injected into, as reported by
     * [dev.getelements.conductor.service.JobProfile.containers] (a
     * [dev.getelements.conductor.ContainerRef.id]). `null` targets the primary container, matching
     * [JobRequest.environment]'s unqualified-addressing convention.
     */
    val containerId: String? = null,

    /**
     * The key within the stored secret whose value to inject, for secrets carrying more than one
     * value. `null` requires the secret to carry exactly one value; naming a key the secret does
     * not have (or omitting it on a multi-value secret) fails the launch before anything is
     * created.
     */
    val key: String? = null,

)
