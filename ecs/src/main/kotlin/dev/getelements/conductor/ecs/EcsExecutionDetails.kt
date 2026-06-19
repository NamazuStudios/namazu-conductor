package dev.getelements.conductor.ecs

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for an ECS [dev.getelements.conductor.JobExecution].
 */
data class EcsExecutionDetails(
    @JsonProperty("cluster")              val cluster: String,
    @JsonProperty("taskDefinitionArn")    val taskDefinitionArn: String,
    @JsonProperty("launchType")           val launchType: String? = null,
    @JsonProperty("lastStatus")           val lastStatus: String? = null
)