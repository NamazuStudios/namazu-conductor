package dev.getelements.conductor.ecs

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for an ECS [dev.getelements.conductor.DaemonExecution]. Unlike
 * [EcsExecutionDetails], there is no stdio token — daemons have no `streamStdio` equivalent.
 */
data class EcsDaemonExecutionDetails(
    @JsonProperty("cluster") val cluster: String,
    @JsonProperty("serviceName") val serviceName: String,
    @JsonProperty("taskDefinitionArn") val taskDefinitionArn: String
)
