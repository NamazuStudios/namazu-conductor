package dev.getelements.conductor

import dev.getelements.conductor.service.JobProfile
import dev.getelements.elements.sdk.annotation.ElementServiceExport

/**
 * Serverside hook consulted by the admin module's REST endpoints (`GET /profiles`, `GET /jobs`,
 * `POST /jobs`, `POST /jobs/stop`) whenever a session asks to list, execute, or stop a job —
 * the REST-side counterpart of [TerminalAttachPolicy] (which covers terminal attach only).
 *
 * A deployed policy Element binds an implementation in its Guice `PrivateModule` and exposes it;
 * the admin module discovers every deployed implementation by scanning the Element registry (the
 * same mechanism it uses to find every [`OrchestrationService`][dev.getelements.conductor.service.OrchestrationService]
 * and every [TerminalAttachPolicy]). The [ElementServiceExport] here is what makes the *type*
 * visible to that scan — a bare Guice `expose()` isn't enough for `ServiceLocator.findInstance`
 * to see it.
 *
 * When no policy Element is deployed the admin module falls back to its historical behaviour:
 * the REST endpoints admit `SUPERUSER` sessions only. When at least one is deployed:
 *
 *  - **Listings** (`GET /profiles`, `GET /jobs`) consult [visibleNamespaces] once per request and
 *    filter every returned row down to what the session may see. Rows are matched by their
 *    namespace ([JobProfile.namespace] for profiles, [JobExecution.namespace] for executions);
 *    rows whose namespace is `null` (providers with no namespace concept — ECS, EdgeGap,
 *    Multiplay) are visible only under [JobVisibility.All]. Visibility is aggregated as a union:
 *    if any policy returns [JobVisibility.All] the session sees everything, otherwise the union of
 *    every policy's [JobVisibility.Namespaces] sets applies. A policy returning [JobVisibility.None]
 *    (or throwing) contributes nothing — the abstention. A [JobVisibility.Namespaces] grant — even
 *    an **empty** set — authorizes the listing (an authorized session that simply has nothing to
 *    see); only when no policy is deployed, or every policy abstains, does the historical
 *    `SUPERUSER`-only rule apply instead.
 *
 *  - **Execute and stop** (`POST /jobs`, `POST /jobs/stop`) consult [authorize] per request.
 *    Every implementation is asked for a [JobAccessVote] and the action is allowed iff at least
 *    one votes [JobAccessVote.ALLOW] and none vote [JobAccessVote.DENY]. [JobAccessVote.PASS] is
 *    an abstention: it is neither a yes nor a veto, and is ignored. A `SUPERUSER` session is
 *    subject to the same vote as any other — there is no superuser bypass once a policy is
 *    deployed. When every vote abstains, the historical `SUPERUSER`-only rule applies.
 *
 * Implementations must be safe to call from any thread and should not block for long.
 */
@ElementServiceExport
interface JobAccessPolicy {

    /**
     * Reports which job namespaces the supplied session may see in listing responses, so the
     * admin module can filter rows serverside rather than trusting the client to hide them.
     */
    fun visibleNamespaces(session: dev.getelements.elements.sdk.model.session.Session,
                          user: dev.getelements.elements.sdk.model.user.User?): JobVisibility

    /**
     * Votes on whether the session in [context] may perform [JobAccessContext.action] against the
     * referenced profile or execution.
     */
    fun authorize(context: JobAccessContext): JobAccessVote

}

/**
 * The authenticated identity and job context a [JobAccessPolicy] decides against. Exactly one of
 * [profile] (an EXECUTE, before dispatch) or [execution] (a STOP, resolved from live workloads)
 * is non-null.
 */
data class JobAccessContext(

    /**
     * The validated session, as authenticated by the platform's auth filter. Never the raw secret.
     */
    val session: dev.getelements.elements.sdk.model.session.Session,

    /**
     * The authenticated user, or `null` for anonymous sessions. Treat `null` as "anonymous".
     */
    val user: dev.getelements.elements.sdk.model.user.User?,

    /**
     * The action being authorized.
     */
    val action: JobAccessAction,

    /**
     * The name of the provider Element that owns the profile or execution.
     */
    val elementName: String,

    /**
     * The profile an EXECUTE is about to launch, or `null` for a STOP.
     */
    val profile: JobProfile? = null,

    /**
     * The execution a STOP targets, as resolved from the provider, or `null` for an EXECUTE.
     */
    val execution: JobExecution? = null,

    /**
     * The effective namespace the action would land in or target — [JobProfile.namespace] for an
     * EXECUTE, [JobExecution.namespace] for a STOP. `null` when the provider has no namespace
     * concept.
     */
    val namespace: String? = null,

)

/** The REST actions a [JobAccessPolicy] can be asked to vote on. */
enum class JobAccessAction {
    EXECUTE,
    STOP,
}

/** A policy implementation's vote on an execute/stop request. */
enum class JobAccessVote {
    ALLOW,
    DENY,
    PASS,
}

/**
 * The listing visibility a [JobAccessPolicy] grants a session. Aggregated as a union across every
 * deployed policy: any [All] wins outright, [Namespaces] sets merge, and [None] contributes
 * nothing (the abstention).
 */
sealed interface JobVisibility {

    /** The session may see every row, including rows with a `null` namespace. */
    object All : JobVisibility

    /** The session may see only rows whose namespace is in [namespaces]. */
    data class Namespaces(val namespaces: Set<String>) : JobVisibility

    /** The session may see nothing this policy grants — an abstention, not a veto. */
    object None : JobVisibility

}
