package dev.getelements.conductor.ecs

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Attribute name constants for the ECS Element. Each constant is the fully-qualified attribute
 * key used by the Elements SDK to bind configuration values via [@Named][com.google.inject.name.Named]
 * injection. Default values are declared on each constant via [@ElementDefaultAttribute].
 */
object EcsAttributes {

    /**
     * The AWS region in which the ECS cluster resides (e.g. `"us-east-1"`).
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val REGION = "dev.getelements.conductor.ecs.region"

    /**
     * The short name or full ARN of the ECS cluster on which tasks are launched.
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val CLUSTER = "dev.getelements.conductor.ecs.cluster"

    /**
     * Comma-separated list of VPC subnet IDs to attach to launched tasks.
     * Required for tasks using `awsvpc` network mode. No default.
     */
    @ElementDefaultAttribute("")
    const val SUBNETS = "dev.getelements.conductor.ecs.subnets"

    /**
     * Comma-separated list of security group IDs to attach to launched tasks.
     * Required for tasks using `awsvpc` network mode. No default.
     */
    @ElementDefaultAttribute("")
    const val SECURITY_GROUPS = "dev.getelements.conductor.ecs.security.groups"

    /**
     * The job set name used to filter task definitions. Only task definitions tagged with
     * `namazu.conductor:jobSet=<value>` matching this attribute are surfaced as profiles.
     * Defaults to `"default"`.
     */
    @ElementDefaultAttribute("default")
    const val JOBSET = "dev.getelements.conductor.ecs.job.set"

    /**
     * Port on which a `namazu-stdio-bridge` sidecar (if included in the task definition's container
     * image) listens for stdio WebSocket connections. ECS has no native container stdio API, so
     * [dev.getelements.conductor.ecs.service.EcsOrchestrationService.streamStdio] depends on this
     * bridge; declare this port in the container's port mappings for it to be reachable.
     */
    @ElementDefaultAttribute("10080")
    const val STDIO_BRIDGE_PORT = "dev.getelements.conductor.ecs.stdio.bridge.port"

    /**
     * Base path prefix for the `namazu-stdio-bridge` WebSocket endpoints — must match the bridge's
     * own `NAMAZU_CONDUCTOR_STDIO_URI`. Defaults to no prefix.
     */
    @ElementDefaultAttribute("")
    const val STDIO_BRIDGE_BASE_PATH = "dev.getelements.conductor.ecs.stdio.bridge.base.path"

}