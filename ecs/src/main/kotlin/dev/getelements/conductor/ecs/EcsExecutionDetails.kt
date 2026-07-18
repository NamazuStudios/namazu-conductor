package dev.getelements.conductor.ecs

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Provider-specific details for an ECS [dev.getelements.conductor.JobExecution].
 */
data class EcsExecutionDetails(
    @JsonProperty("cluster")              val cluster: String,
    @JsonProperty("taskDefinitionArn")    val taskDefinitionArn: String,
    @JsonProperty("launchType")           val launchType: String? = null,
    @JsonProperty("lastStatus")           val lastStatus: String? = null,

    /**
     * The per-execution `namazu-stdio-bridge` access token generated in
     * [dev.getelements.conductor.ecs.service.EcsOrchestrationService.execute], carried forward so
     * [dev.getelements.conductor.ecs.service.EcsOrchestrationService.streamStdio] can present it
     * later. `@JsonIgnore`d — [dev.getelements.conductor.JobExecution.details] is "serialised as-is
     * by the REST layer," and this is a secret, not something to expose there.
     */
    @JsonIgnore
    val stdioToken: String? = null
)