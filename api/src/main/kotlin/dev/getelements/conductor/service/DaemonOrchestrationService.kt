package dev.getelements.conductor.service

import dev.getelements.conductor.DaemonExecution
import dev.getelements.conductor.DaemonRequest
import dev.getelements.elements.sdk.annotation.ElementServiceExport

/**
 * Orchestrates persistent, long-running workloads in the cloud. Unlike [OrchestrationService], which
 * models one-shot job execution through to a terminal state, implementations of this interface manage
 * daemons: workloads with a desired replica count that the underlying platform keeps running
 * indefinitely. There is no completion — [dev.getelements.conductor.DaemonStatus.RUNNING] is the
 * steady state.
 *
 * A provider may implement [OrchestrationService], [DaemonOrchestrationService], or both; neither
 * requires the other.
 */
@ElementServiceExport
interface DaemonOrchestrationService {

    /**
     * Queries the subsystem to get the available [Daemon]s that can be used to deploy a daemon. This
     * returns an opaque interface which may only be consumed by the same object which returned it.
     *
     * @return a list of [Daemon]s supported by this orchestrator implementation
     */
    fun getAvailableDaemons(): List<Daemon>

    /**
     * Finds the available daemon with the supplied id returning null if it does not exist.
     */
    fun findAvailableDaemon(id : String): Daemon? {
        return getAvailableDaemons().find { it.id == id }
    }

    /**
     * Deploys the daemon described by the supplied [DaemonRequest].
     */
    fun deploy(request : DaemonRequest): DaemonExecution

    /**
     * Undeploys the daemon identified by [execution], releasing any resources the underlying
     * platform allocated for it.
     */
    fun undeploy(execution: DaemonExecution)

    /**
     * Returns a fresh snapshot of the current status of [execution].
     */
    fun getStatus(execution: DaemonExecution): DaemonExecution

    /**
     * Sets the desired replica count for [execution], returning a fresh snapshot reflecting the
     * change. Note that an active autoscaling policy may reassert its own desired count on its next
     * reconcile if the manually-set count doesn't match current scaling conditions.
     */
    fun setDesiredCount(execution: DaemonExecution, desired: Int): DaemonExecution

    /**
     * Sets the autoscaling bounds for [execution], returning a fresh snapshot reflecting the change.
     * Enables autoscaling if it was not already configured for this daemon.
     */
    fun setScalingBounds(execution: DaemonExecution, min: Int, max: Int): DaemonExecution

}
