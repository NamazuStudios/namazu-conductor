package dev.getelements.conductor

import dev.getelements.elements.sdk.annotation.ElementServiceExport

/**
 * Serverside hook consulted whenever a client requests a terminal attach to a running job's
 * stdio. Replaces the admin module's historically hardcoded SUPERUSER-only gate with a
 * pluggable authorization decision, so a tenant can deploy a custom Element that decides — in
 * arbitrary serverside code — which sessions may attach to which jobs.
 *
 * A deployed policy Element binds an implementation in its Guice `PrivateModule` and exposes it;
 * the admin module discovers every deployed implementation by scanning the Element registry (the
 * same mechanism it uses to find every [`OrchestrationService`][dev.getelements.conductor.service.OrchestrationService]).
 * The [ElementServiceExport] here is what makes the *type* visible to that scan — a bare Guice
 * `expose()` isn't enough for `ServiceLocator.findInstance` to see it.
 *
 * When no policy Element is deployed the admin module falls back to its historical behaviour:
 * only `SUPERUSER` sessions may attach. When at least one is deployed, every implementation is
 * asked for a [TerminalAttachVote] and the attach is allowed iff at least one votes
 * [TerminalAttachVote.ALLOW] and none vote [TerminalAttachVote.DENY]. [TerminalAttachVote.PASS]
 * is an abstention: it is neither a yes nor a veto, and is ignored. A `SUPERUSER` session is
 * subject to the same vote as any other — there is no superuser bypass when a policy is
 * deployed.
 */
@ElementServiceExport
interface TerminalAttachPolicy {

    /**
     * Votes on whether the authenticated session in [context] may attach a terminal to the
     * referenced job. Implementations must be safe to call from any thread and should not block
     * for long: the admin module awaits all votes within the WebSocket handshake's auth timeout.
     */
    fun authorize(context: TerminalAttachContext): TerminalAttachVote

}

/**
 * The authenticated identity and job context a [TerminalAttachPolicy] decides against.
 */
data class TerminalAttachContext(

    /**
     * The validated session originally presented by the client, after the admin module ran
     * `SessionService.checkAndRefreshSessionIfNecessary` on its secret. Never the raw secret
     * itself — handing a live bearer token to thirdparty code is deliberately avoided.
     */
    val session: dev.getelements.elements.sdk.model.session.Session,

    /**
     * The authenticated user, or `null` for anonymous sessions (the SDK returns sessions whose
     * user is null, or a user at the `UNPRIVILEGED` level — there is no `ANON` level). Treat null
     * as "anonymous".
     */
    val user: dev.getelements.elements.sdk.model.user.User?,

    /**
     * The id of the job the client requested a terminal for.
     */
    val jobId: String,

    /**
     * The container within the job the terminal would attach to, or `null` for the primary
     * container.
     */
    val containerId: String?,

    /**
     * An optional command override the client requested, or `null` for the container's default.
     */
    val command: List<String>?,

    /**
     * The name of the provider Element that owns [execution].
     */
    val elementName: String,

    /**
     * The execution being attached to, as resolved by the provider's
     * [dev.getelements.conductor.service.OrchestrationService.listExecutions].
     */
    val execution: JobExecution,

    /**
     * The effective namespace the job's workload was created in, or `null` for providers without
     * a namespace concept (ECS, EdgeGap).
     */
    val namespace: String?,

)

/**
 * A policy implementation's vote. [TerminalAttachVote.ALLOW] counts as a yes,
 * [TerminalAttachVote.DENY] vetoes outright, and [TerminalAttachVote.PASS] abstains — a session
 * that only pass is treated as if no policy Element were deployed, falling back to the historical
 * SUPERUSER-only rule.
 */
enum class TerminalAttachVote {
    ALLOW,
    DENY,
    PASS,
}