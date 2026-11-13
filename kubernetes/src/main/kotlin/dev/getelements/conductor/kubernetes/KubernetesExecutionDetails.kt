package dev.getelements.conductor.kubernetes

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for a Kubernetes [dev.getelements.conductor.JobExecution].
 */
data class KubernetesExecutionDetails(
    @JsonProperty("namespace")    val namespace: String,
    @JsonProperty("workloadKind") val workloadKind: String,
    @JsonProperty("name")         val name: String,
    /**
     * The node the workload's pod is scheduled on, from `Pod.spec.nodeName` (null for
     * batch-Job rows — a Job object carries no node; only its pods do). Exposed so callers
     * can correlate node occupancy per tenant: under shared-capacity NodeClasses the EC2
     * node carries no tenant identity, and the scheduling node is the only per-tenant node
     * attribution that exists (#472).
     */
    @JsonProperty("nodeName")     val nodeName: String? = null
)