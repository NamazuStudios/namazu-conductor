package dev.getelements.conductor.kubernetes

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for a Kubernetes [dev.getelements.conductor.JobExecution].
 */
data class KubernetesExecutionDetails(
    @JsonProperty("namespace")    val namespace: String,
    @JsonProperty("workloadKind") val workloadKind: String,
    @JsonProperty("name")         val name: String
)