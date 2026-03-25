# ECS Provider

The `ecs` module implements `OrchestrationService` for AWS ECS, supporting both Fargate and EC2 launch types. Behavior is driven by tags on ECS task definitions rather than service-level configuration, so each task definition declares its own runtime requirements.

## Configuration Attributes

| Attribute | Key | Default | Description |
|---|---|---|---|
| Region | `dev.getelements.conductor.ecs.region` | _(required)_ | AWS region of the ECS cluster |
| Cluster | `dev.getelements.conductor.ecs.cluster` | _(required)_ | Short name or ARN of the ECS cluster |
| Subnets | `dev.getelements.conductor.ecs.subnets` | _(required for `awsvpc`)_ | Comma-separated VPC subnet IDs |
| Security Groups | `dev.getelements.conductor.ecs.security.groups` | _(required for `awsvpc`)_ | Comma-separated security group IDs |

## Task Definition Tags

The ECS provider reads the following tags from each task definition at profile-discovery time. Tags are set on the task definition in the AWS Console, via the AWS CLI, or in your infrastructure-as-code (Terraform, CDK, CloudFormation).

### `conductor:launchType`

Controls the ECS launch type used when running the task.

| Value | Behaviour |
|---|---|
| `FARGATE` | Task runs on AWS Fargate (serverless). Default if tag is absent. |
| `EC2` | Task runs on an EC2 container instance in the cluster. |
| `EXTERNAL` | Task runs on an external instance registered via ECS Anywhere. |

**Example:**
```
conductor:launchType = FARGATE
```

### `conductor:assignPublicIp`

Controls whether a public IP is assigned to the task's elastic network interface. Only applies to tasks using `awsvpc` network mode. Defaults to `DISABLED` if the tag is absent.

| Value | Behaviour |
|---|---|
| `ENABLED` | A public IP is assigned. The task is reachable from the internet (subject to security group rules). |
| `DISABLED` | No public IP is assigned. The task is reachable only from within the VPC. Default if tag is absent. |

**Example:**
```
conductor:assignPublicIp = ENABLED
```

## Network Configuration

VPC network configuration (subnets, security groups, public IP assignment) is applied automatically when the task definition's network mode is `awsvpc`. For EC2 tasks using `bridge` or `host` network mode, no network configuration is applied.

Security groups do not need to be scoped to specific ports — a single security group covering the range of ports your tasks may expose is sufficient. The precise ports each container listens on are declared in the task definition's port mappings and are surfaced as `JobEndpoint`s on the `JobExecution` once the task reaches `RUNNING`.

## Integration Test

The module includes an integration test (`EcsOrchestrationServiceIT`) that launches a real ECS task and verifies it serves HTTP traffic. It requires the following environment variables:

| Variable | Description |
|---|---|
| `AWS_ACCESS_KEY_ID` | AWS access key (read by the SDK automatically) |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key (read by the SDK automatically) |
| `AWS_REGION` | AWS region of the ECS cluster |
| `ECS_CLUSTER` | Short name or ARN of the ECS cluster |
| `ECS_SUBNETS` | Comma-separated VPC subnet IDs |
| `ECS_SECURITY_GROUPS` | Comma-separated security group IDs |
| `ECS_TASK_FAMILY` | _(optional)_ Task definition family name. Defaults to `conductor-integration-test`. |
| `CFN_IMAGE_URI` | _(optional)_ Container image URI passed to the CloudFormation stack as `ImageUri`. Defaults to `nginx:latest`. Set this to your ECR image URI to test a custom image. |

The test is skipped automatically if any required variable is absent.

**Task definition prerequisites:**
- Family name: `conductor-integration-test` (or the value of `ECS_TASK_FAMILY`)
- Network mode: `awsvpc`, Fargate-compatible
- Container: an HTTP server on port 80 (e.g. nginx)
- Tags: `conductor:launchType=FARGATE`, `conductor:assignPublicIp=ENABLED`
- Security group: allows inbound TCP on port 80

```bash
AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 \
ECS_CLUSTER=my-cluster ECS_SUBNETS=subnet-abc ECS_SECURITY_GROUPS=sg-xyz \
mvn verify -pl ecs
```