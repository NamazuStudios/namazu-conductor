# AWS ECS Provider

## What Is AWS ECS?

[Amazon Elastic Container Service (ECS)](https://aws.amazon.com/ecs/) is AWS's managed container orchestration platform. ECS runs Docker containers as **tasks** within a **cluster** - a group of compute capacity that you define and control. Unlike fleet providers such as EdgeGap, ECS is tightly integrated with the rest of the AWS ecosystem: your containers run inside your own VPC, on infrastructure in a specific AWS region, with access to your IAM roles, load balancers, RDS databases, and other AWS services.

This deep AWS integration makes ECS well-suited to workloads that need to run alongside persistent infrastructure - but it also means ECS requires more upfront configuration than a managed fleet provider.

**AWS ECS resources:**

- [Amazon ECS Documentation](https://docs.aws.amazon.com/ecs/)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [AWS Fargate on ECS](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Networking (awsvpc)](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-networking-awsvpc.html)
- [IAM Roles for ECS Tasks](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html)
- [Amazon ECR - Container Registry](https://docs.aws.amazon.com/ecr/)

---

## Why ECS for Game Servers?

| Concern | ECS |
|---|---|
| Lifecycle | Long-running; tasks can run indefinitely without a session boundary |
| Region | Locked to the AWS region(s) you configure; no automatic global placement |
| Infrastructure | Runs inside your VPC with access to all your AWS resources |
| Cost model | Fargate: per-second billing while task runs; EC2: reserved or spot capacity |
| Setup complexity | Higher - requires VPC, subnets, security groups, task definitions, IAM roles, and resource tags |
| Integration | Native access to RDS, ElastiCache, S3, CloudWatch, Secrets Manager, and other AWS services |

ECS is best suited to **infrastructure that must persist indefinitely** - servers that exist regardless of whether a player is connected. A persistent world zone in an MMORPG is the canonical example: the Ironforge district does not start when a player logs in and stop when they log out. It runs continuously, maintains state, and must be reachable at any moment. This kind of workload belongs on ECS, not on an on-demand fleet provider.

---

## ECS Concepts

### Clusters

An ECS **cluster** is the logical grouping of compute capacity on which your tasks run. All tasks launched by Conductor run within a single cluster you specify in configuration. If you are using Fargate, the cluster is essentially just a namespace - AWS manages the underlying compute. If you are using EC2 launch type, the cluster also contains the EC2 instances that run your containers.

### Task Definitions

A **task definition** is the blueprint for a container workload. It specifies the Docker image, CPU and memory allocation, port mappings, IAM role, environment variables, log configuration, and other runtime parameters. A task definition has a **family** name - the stable identifier for that workload across revisions. When you update a task definition, ECS creates a new revision under the same family name.

Namazu Conductor uses the task definition family name as the `JobProfile` identifier. Each family registered in your ECS account and tagged correctly appears as a discoverable profile your backend code can select by name.

### Launch Types

ECS supports two launch types:

- **FARGATE** - AWS provisions and manages the underlying compute. You pay only for the vCPU and memory consumed while the task runs. No EC2 instances to manage. Each Fargate task requires `awsvpc` network mode and gets its own elastic network interface. Fargate is simpler to operate but tends to be more expensive per unit of compute than equivalent EC2 capacity, since you are paying for the managed infrastructure overhead in addition to the raw resources.
- **EC2** - Your tasks run on EC2 instances you manage in the cluster. Supports both `awsvpc` and `bridge` network modes. EC2 is more involved to set up - you are responsible for provisioning and maintaining the instances, configuring the ECS agent, and managing capacity - but it is generally cheaper than Fargate at the same compute size and gives you full control over the underlying host. EC2 also unlocks GPU instance types (such as the `g4dn` and `p3` families), which Fargate does not support. If your game has AI mechanics - procedural NPC behaviour, dynamic dialogue, or any feature powered by a large language model - you can run inference workloads on-demand in the same ECS cluster using GPU instances, bringing the model into your existing infrastructure without a separate AI hosting service.

The launch type is specified per task definition via an ECS resource tag (see below). Conductor reads this tag to select the correct API parameters when starting the task.

### Network Modes

- **awsvpc** - Each task gets its own elastic network interface (ENI) and private IP address within your VPC. Required for Fargate. Conductor resolves the task's public or private IP by querying the ENI after the task starts.
- **bridge** - Containers share the EC2 host's network namespace with port mapping. Used with EC2 launch type when `awsvpc` is not needed or not practical.

### IAM Roles

ECS tasks use two distinct IAM roles:

- **Task execution role** - allows ECS to pull your container image from ECR and send logs to CloudWatch. This role is assumed by the ECS agent, not by your application code.
- **Task role** - assumed by your application code at runtime. Grants the running container permission to call AWS APIs (S3, DynamoDB, Secrets Manager, etc.).

Both roles must be configured in the task definition before Conductor can start the task.

---

## Setup Requirements

ECS requires more upfront AWS infrastructure than EdgeGap. Before Conductor can start tasks, you need:

1. **An ECS cluster** - created in the AWS console or via CloudFormation/Terraform.
2. **A VPC with subnets** - Conductor needs subnet IDs to place tasks. Fargate tasks typically use private subnets with a NAT gateway, or public subnets with a public IP assigned.
3. **Security groups** - defines which ports the task's network interface accepts traffic on. Ensure your game's UDP/TCP ports are open.
4. **Task definitions** - at least one registered in ECS, tagged with the required Conductor tags (see below).
5. **IAM roles** - task execution role (for ECS agent) and task role (for application code).
6. **ECR repository** (optional but typical) - stores your container image within AWS. Alternatively use any Docker registry accessible from your VPC.
7. **AWS credentials** - an IAM user or role with permissions to call `ecs:RunTask`, `ecs:StopTask`, `ecs:DescribeTasks`, `ecs:ListTaskDefinitions`, `ecs:DescribeTaskDefinition`, `ecs:ListTagsForResource`, `ec2:DescribeNetworkInterfaces`, and `ec2:DescribeInstances`.

---

## Tagging Task Definitions

Conductor discovers task definitions and reads their configuration through ECS resource tags. A task definition must carry the following tags to be visible to Conductor:

| Tag Key | Required | Values | Purpose |
|---|---|---|---|
| `namazu.conductor:jobSet` | Yes | any string (e.g. `default`) | Scopes which Conductor instance owns this task definition |
| `namazu.conductor:launchType` | Yes | `FARGATE` or `EC2` | Tells Conductor which ECS launch type to use |
| `namazu.conductor:assignPublicIp` | No | `ENABLED` or `DISABLED` | Whether to assign a public IP to the task's ENI (Fargate/awsvpc only) |

**jobSet** is important when you run multiple Conductor instances or environments (e.g. staging vs production) against the same AWS account. Each Conductor instance is configured with a jobSet name and only sees task definitions tagged with that name. This prevents a production Conductor from accidentally discovering staging task definitions and vice versa.

Tags can be applied in the AWS console when registering a task definition, or via the CLI:

```bash
aws ecs tag-resource \
  --resource-arn arn:aws:ecs:us-east-1:123456789012:task-definition/my-zone-server:1 \
  --tags key=namazu.conductor:jobSet,value=default \
         key=namazu.conductor:launchType,value=FARGATE \
         key=namazu.conductor:assignPublicIp,value=ENABLED
```

---

## Configuration

The ECS element is configured via Namazu Elements attributes:

| Attribute | Key | Default |
|---|---|---|
| AWS Region | `dev.getelements.conductor.ecs.region` | *(required, no default)* |
| Cluster | `dev.getelements.conductor.ecs.cluster` | *(required, no default)* |
| Subnets | `dev.getelements.conductor.ecs.subnets` | *(required, no default)* |
| Security Groups | `dev.getelements.conductor.ecs.security.groups` | *(required, no default)* |
| Job Set | `dev.getelements.conductor.ecs.job.set` | `default` |

Set `dev.getelements.conductor.ecs.region` to the AWS region your cluster lives in (e.g. `us-east-1`). Set `dev.getelements.conductor.ecs.cluster` to the cluster name or ARN. Provide a comma-separated list of subnet IDs and security group IDs for the remaining two required attributes. The job set defaults to `default` and only needs to be changed if you are running multiple isolated Conductor environments.

---

## Code Examples

All examples use the `OrchestrationService` interface injected via Guice. The ECS implementation is wired by `EcsOrchestrationModule` - your backend code depends only on `OrchestrationService` and never on ECS-specific types directly.

### Listing Available Profiles

Discover the task definition families visible to this Conductor instance:

```kotlin
@Inject
lateinit var orchestration: OrchestrationService

fun listProfiles() {
    val profiles = orchestration.getAvailableProfiles()
    profiles.forEach { profile ->
        println("Available profile: ${profile.id}")
    }
}
```

Profile IDs are task definition family names, for example `ironforge-zone` or `auction-house-server`.

### Starting a Persistent Zone Server

Find a profile by ID and launch a task. Pass environment variables to configure the server at runtime:

```kotlin
fun startZoneServer(zoneId: String, configUrl: String): JobExecution {
    val profile = orchestration.findAvailableProfile("ironforge-zone")
        ?: error("Profile not found - check task definition tags in ECS")

    return orchestration.execute(
        JobRequest(
            profile = profile,
            environment = mapOf(
                "ZONE_ID"      to zoneId,
                "CONFIG_URL"   to configUrl,
                "BACKEND_URL"  to "https://api.mygame.com",
                "BACKEND_SECRET" to "<shared secret>"
            )
        )
    )
}
```

### Waiting for the Server to Be Ready

`execute()` returns immediately with a `PENDING` execution. Use `getFutureForStatus()` to block until the task is `RUNNING` and its endpoints are available:

```kotlin
fun waitForServer(execution: JobExecution): JobExecution {
    val running = orchestration
        .getFutureForStatus(execution, JobStatus.RUNNING)
        .get(10, TimeUnit.MINUTES)   // ECS cold starts can take longer than fleet providers

    val endpoint = running.endpoints.first()
    println("Server ready at ${endpoint.host}:${endpoint.port} (${endpoint.protocol})")
    return running
}
```

Once `RUNNING`, `JobExecution.endpoints` contains the host and port for each exposed container port. For Fargate tasks with `assignPublicIp=ENABLED`, this is the task's public IP address. For EC2/bridge tasks, this is the EC2 host IP with the mapped port.

### Placement - Targeting a Specific Region

ECS tasks always run in the region and cluster you configured. Unlike EdgeGap, there is no per-request global placement - the region is fixed at configuration time. If you need servers in multiple regions, deploy separate Conductor instances, each configured for a different AWS region and cluster.

You can pass a `RegionPlacement` hint, but ECS will only honour it if it matches the configured region. For ECS, region selection is an infrastructure decision made at deploy time, not at runtime.

```kotlin
val execution = orchestration.execute(
    JobRequest(
        profile = profile,
        placement = listOf(RegionPlacement(region = "us-east-1")),
        environment = mapOf("ZONE_ID" to zoneId)
    )
)
```

### Stopping a Server

Stop a running task when it is no longer needed:

```kotlin
fun stopServer(execution: JobExecution) {
    orchestration.stop(execution)
}
```

For persistent zone servers, `stop()` is typically called only during maintenance, a scheduled shutdown, or a controlled server migration - not at the end of every player session. Unlike session-scoped fleet servers, a persistent zone server may run for days, weeks, or indefinitely.

---

## A Persistent Zone Lifecycle

A typical lifecycle for a persistent MMORPG zone looks like this:

```kotlin
// 1. Backend starts on deploy - launch all persistent zone servers
val ironforgeExecution = startZoneServer(
    zoneId    = "ironforge-district",
    configUrl = "https://config.mygame.com/zones/ironforge"
)

// 2. Wait for the zone to be ready before accepting player connections
val running = waitForServer(ironforgeExecution)

// 3. Register the zone's address in the backend's zone directory
val endpoint = running.endpoints.first()
zoneDirectory.register("ironforge-district", endpoint.host, endpoint.port)

// 4. Players look up the zone address from the backend and connect directly
//    The zone server runs indefinitely, handling connections and disconnections

// 5. On controlled shutdown (maintenance window, server migration)
stopServer(running)
```

Unlike a session server, the zone server is never stopped between player connections. Players arrive and depart, but the server persists. Any durable state - player positions, world state, economy data - is written out to your AWS infrastructure (RDS, DynamoDB, ElastiCache) so it survives a restart if the task is ever replaced.

---

## Comparison to EdgeGap

| Aspect | ECS | EdgeGap |
|---|---|---|
| Best for | Persistent, always-on servers | On-demand, session-scoped servers |
| Region | Fixed at configuration time | Automatic player-proximity routing |
| Setup | VPC, subnets, security groups, IAM roles, tags | API key only |
| AWS integration | Full - same VPC as your databases | None |
| Billing | Per-second (Fargate) or reserved (EC2) | Per-second |
| Cold start | Slower (image pull + ENI provisioning) | Fast (seconds) |
| Indefinite runtime | Yes | Possible, but not the intended use case |