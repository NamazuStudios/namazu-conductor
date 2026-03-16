package dev.getelements.conductor.fargate

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute

/**
 * Attribute name constants for the Fargate Element. Each constant is the fully-qualified attribute
 * key used by the Elements SDK to bind configuration values via [@Named][com.google.inject.name.Named]
 * injection. Default values are declared on each constant via [@ElementDefaultAttribute].
 */
object FargateAttributes {

    /**
     * The AWS region in which the ECS cluster resides (e.g. `"us-east-1"`).
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val REGION = "dev.getelements.conductor.fargate.region"

    /**
     * The short name or full ARN of the ECS cluster on which tasks are launched.
     * No default — must be supplied by the operator.
     */
    @ElementDefaultAttribute("")
    const val CLUSTER = "dev.getelements.conductor.fargate.cluster"

    /**
     * Comma-separated list of VPC subnet IDs to attach to launched tasks.
     * Required for Fargate `awsvpc` networking. No default.
     */
    @ElementDefaultAttribute("")
    const val SUBNETS = "dev.getelements.conductor.fargate.subnets"

    /**
     * Comma-separated list of security group IDs to attach to launched tasks.
     * Required for Fargate `awsvpc` networking. No default.
     */
    @ElementDefaultAttribute("")
    const val SECURITY_GROUPS = "dev.getelements.conductor.fargate.security.groups"

    /**
     * Whether to assign a public IP to launched tasks. Must be `"ENABLED"` or `"DISABLED"`.
     * Defaults to `"ENABLED"`.
     */
    @ElementDefaultAttribute("ENABLED")
    const val ASSIGN_PUBLIC_IP = "dev.getelements.conductor.fargate.assign.public.ip"

}