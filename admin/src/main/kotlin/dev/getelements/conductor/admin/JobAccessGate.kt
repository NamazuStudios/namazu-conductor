package dev.getelements.conductor.admin

import dev.getelements.conductor.JobAccessContext
import dev.getelements.conductor.JobAccessVote
import dev.getelements.conductor.JobExecution
import dev.getelements.conductor.JobVisibility
import dev.getelements.conductor.service.JobProfile
import dev.getelements.elements.sdk.model.session.Session
import dev.getelements.elements.sdk.model.user.User
import org.slf4j.LoggerFactory

/**
 * Aggregates every deployed [dev.getelements.conductor.JobAccessPolicy] for the admin REST
 * endpoints, mirroring [TerminalSessionHandler]'s aggregation of
 * [dev.getelements.conductor.TerminalAttachPolicy] votes for terminal attach:
 *
 *  - **Votes** (execute/stop): any `DENY` vetoes outright, a single `ALLOW` suffices, `PASS`
 *    abstains. A policy that throws is treated as `PASS`.
 *
 *  - **Visibility** (listings): a union — any [JobVisibility.All] wins outright, otherwise the
 *    union of every [JobVisibility.Namespaces] set applies. A policy that throws is treated as
 *    [JobVisibility.None] (the abstention). When no policy is deployed, or every policy abstains,
 *    [visibility] returns `null` so the caller falls back to the historical `SUPERUSER`-only
 *    rule. A `Namespaces` grant — even an empty set — authorizes the listing.
 */
internal object JobAccessGate {

    private val logger = LoggerFactory.getLogger(JobAccessGate::class.java)

    /** The aggregate of one [JobAccessPolicy.authorize] round. */
    sealed interface Decision {
        /** At least one policy voted `DENY` — vetoed, regardless of who's asking. */
        object Denied : Decision

        /** At least one policy voted `ALLOW` and none voted `DENY`. */
        object Allowed : Decision

        /** No policy voted either way (or none is deployed) — apply the historical rule. */
        object NoDecision : Decision
    }

    /**
     * Resolves the listing visibility for [session]/[user] as the union across every deployed
     * [dev.getelements.conductor.JobAccessPolicy], or `null` when no policy is deployed (or every
     * one abstained) so the caller falls back to the historical `SUPERUSER`-only rule.
     */
    fun visibility(callerClass: Class<*>, session: Session, user: User?): JobVisibility? {
        var sawGrant = false
        var sawAll = false
        var union: MutableSet<String>? = null

        ElementLookup.forEachJobAccessPolicy(callerClass) { elementName, policy ->
            val v = try {
                policy.visibleNamespaces(session, user)
            } catch (e: Exception) {
                logger.warn("JobAccessPolicy [{}] threw in visibleNamespaces(); treating as None", elementName, e)
                JobVisibility.None
            }
            when (v) {
                is JobVisibility.All -> { sawGrant = true; sawAll = true }
                is JobVisibility.Namespaces -> {
                    sawGrant = true
                    if (union == null) union = mutableSetOf()
                    union!!.addAll(v.namespaces)
                }
                JobVisibility.None -> {}
            }
        }

        if (!sawGrant) return null
        return if (sawAll) JobVisibility.All else JobVisibility.Namespaces(union ?: emptySet())
    }

    /**
     * Aggregates one [JobAccessPolicy.authorize] round for [context]: any `DENY` vetoes outright,
     * otherwise a single `ALLOW` suffices, otherwise [Decision.NoDecision] (apply the historical
     * `SUPERUSER`-only rule).
     */
    fun authorize(callerClass: Class<*>, context: JobAccessContext): Decision {
        var denied = false
        var allowed = false

        ElementLookup.forEachJobAccessPolicy(callerClass) { elementName, policy ->
            val vote = try {
                policy.authorize(context)
            } catch (e: Exception) {
                logger.warn("JobAccessPolicy [{}] threw in authorize(); treating as PASS", elementName, e)
                JobAccessVote.PASS
            }
            when (vote) {
                JobAccessVote.DENY -> denied = true
                JobAccessVote.ALLOW -> allowed = true
                JobAccessVote.PASS -> {}
            }
        }

        return when {
            denied -> Decision.Denied
            allowed -> Decision.Allowed
            else -> Decision.NoDecision
        }
    }

    /** True when [profile]'s row may appear in a listing response under [visibility]. */
    fun profileVisible(profile: JobProfile, visibility: JobVisibility): Boolean = when (visibility) {
        JobVisibility.All -> true
        is JobVisibility.Namespaces -> profile.namespace in visibility.namespaces
        JobVisibility.None -> false
    }

    /** True when [execution]'s row may appear in a listing response under [visibility]. */
    fun executionVisible(execution: JobExecution, visibility: JobVisibility): Boolean = when (visibility) {
        JobVisibility.All -> true
        is JobVisibility.Namespaces -> execution.namespace in visibility.namespaces
        JobVisibility.None -> false
    }

}
