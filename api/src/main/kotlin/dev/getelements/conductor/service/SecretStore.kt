package dev.getelements.conductor.service

import dev.getelements.elements.sdk.annotation.ElementServiceExport

/**
 * A first-class, durable secrets store for credentials that jobs reference by name instead of
 * receiving literal values (issue #84). Values are written once and never echoed by listing;
 * retrieval is an explicit, authorized [recall].
 *
 * The admin element discovers every deployed implementation by scanning the Element registry (the
 * same mechanism it uses for [OrchestrationService]) and exposes the store over its REST surface.
 * Conductor itself ships one implementation: the Kubernetes provider backs the store with
 * Kubernetes `Secret`s in its configured namespace. Providers without a store simply don't expose
 * this service; a launch carrying a [dev.getelements.conductor.SecretRef] against such a provider
 * fails outright.
 */
@ElementServiceExport
interface SecretStore {

    /**
     * Creates or replaces the secret named [name]. The values are durable after this call and are
     * never returned by [list] — only by an explicit [recall]. Implementations may impose naming
     * rules (e.g. Kubernetes' DNS-1123 subdomain names) and throw — loudly, before any state
     * changes — when [name] violates them.
     */
    fun store(name: String, values: Map<String, String>, metadata: Map<String, String> = emptyMap())

    /**
     * Lists the stored secrets by name and metadata only — never their values.
     */
    fun list(): List<SecretSummary>

    /**
     * Recalls the secret named [name] — values and metadata — or `null` when no such secret
     * exists.
     */
    fun recall(name: String): StoredSecret?

    /**
     * Deletes the secret named [name]. Deleting a secret a running workload references does not
     * affect workloads already launched (injection resolves the secret at launch time; Kubernetes
     * resolves `secretKeyRef` from the workload's own namespace at schedule time and keeps serving
     * mounted values) — but subsequent launches referencing it will fail.
     */
    fun delete(name: String)

}

/**
 * A stored secret's name and metadata, as returned by [SecretStore.list] — deliberately without
 * values.
 */
data class SecretSummary(

    /**
     * The secret's name, as accepted by [SecretStore.recall].
     */
    val name: String,

    /**
     * Caller-supplied metadata recorded at store time (e.g. a rotation date or owning team).
     */
    val metadata: Map<String, String>,

)

/**
 * A stored secret, as returned by [SecretStore.recall].
 */
data class StoredSecret(

    /**
     * The secret's name.
     */
    val name: String,

    /**
     * The secret's values, keyed by value name. A [dev.getelements.conductor.SecretRef] with no
     * explicit `key` requires exactly one entry here.
     */
    val values: Map<String, String>,

    /**
     * Caller-supplied metadata recorded at store time.
     */
    val metadata: Map<String, String>,

)
