# ECS Provider

The `ecs` module implements `OrchestrationService` for AWS ECS, supporting both Fargate and EC2 launch types. Behavior is driven by tags on ECS task definitions rather than service-level configuration, so each task definition declares its own runtime requirements.

## Configuration Attributes

| Attribute | Key | Default | Description |
|---|---|---|---|
| Cluster | `dev.getelements.conductor.ecs.cluster` | _(required)_ | Short name or ARN of the ECS cluster |
| Subnets | `dev.getelements.conductor.ecs.subnets` | _(required for `awsvpc`)_ | Comma-separated VPC subnet IDs |
| Security Groups | `dev.getelements.conductor.ecs.security.groups` | _(required for `awsvpc`)_ | Comma-separated security group IDs |
| Jobset | `dev.getelements.conductor.ecs.job.set` | `default` | Only task definitions tagged with `namazu.conductor:jobSet` matching this value are surfaced as profiles |
| Job set name | `dev.getelements.conductor.ecs.job.set.name` | `default` | Friendly, human-readable name for this job set, shown in the admin dashboard wherever the raw job set value would otherwise be displayed |
| Job set description | `dev.getelements.conductor.ecs.job.set.description` | _(empty)_ | Optional Markdown description of this job set, rendered in the admin dashboard's Available Jobs / Running Jobs pages |
| Stdio Bridge Port | `dev.getelements.conductor.ecs.stdio.bridge.port` | `10080` | Port a `namazu-stdio-bridge` sidecar (if included in the task's image) listens on for `streamStdio`. Must be declared in the container's port mappings to be reachable. |
| Stdio Bridge Base Path | `dev.getelements.conductor.ecs.stdio.bridge.base.path` | _(none)_ | Must match the bridge's own `NAMAZU_CONDUCTOR_STDIO_URI`. |

## Task Definition Tags

All tags Conductor *interprets* use the `namazu.conductor:` prefix and are covered below. A task definition may also carry arbitrary non-`namazu.conductor` tags — see [Metadata](#metadata).

The interpreted tags are set on the task definition in the AWS Console, via the AWS CLI, or in your infrastructure-as-code (Terraform, CDK, CloudFormation).

### `namazu.conductor:jobSet`

**Required.** Identifies which conductor instance owns this task definition. Only task definitions whose `namazu.conductor:jobSet` value matches the conductor's configured `jobset` attribute are returned by `getAvailableProfiles()`. This prevents multiple conductor instances sharing a cluster from seeing each other's task definitions.

```
namazu.conductor:jobSet = default
```

The `job.set.name`/`job.set.description` attributes don't affect discovery — they're purely
cosmetic, giving the admin dashboard a friendly label and Markdown blurb for this job set instead
of showing the raw `jobset` value.

### `namazu.conductor:displayName`

An optional friendly display name the admin dashboard shows in place of the raw task-definition
family id. Absent → the raw id is shown.

```
namazu.conductor:displayName = Smoke Test
```

### `namazu.conductor:launchType`

Controls the ECS launch type used when running the task.

| Value | Behaviour |
|---|---|
| `FARGATE` | Task runs on AWS Fargate (serverless). Default if tag is absent. |
| `EC2` | Task runs on an EC2 container instance in the cluster. |
| `EXTERNAL` | Task runs on an external instance registered via ECS Anywhere. |

```
namazu.conductor:launchType = FARGATE
```

### `namazu.conductor:assignPublicIp`

Controls whether a public IP is assigned to the task's elastic network interface. Only applies to tasks using `awsvpc` network mode. Defaults to `DISABLED` if the tag is absent.

| Value | Behaviour |
|---|---|
| `ENABLED` | A public IP is assigned. The task is reachable from the internet (subject to security group rules). |
| `DISABLED` | No public IP is assigned. The task is reachable only from within the VPC. Default if tag is absent. |

```
namazu.conductor:assignPublicIp = ENABLED
```

## Metadata

Beyond the `namazu.conductor:` tags above, a task definition family may carry **any other tags at
all**. Conductor reports the family's complete tag map as `JobProfile.metadata` /
`Daemon.metadata` — verbatim, under the full tag keys, with nothing stripped or filtered out. A
`namazu.conductor:jobSet` tag therefore appears there as well as in the discovery logic; that is
deliberate, so a consumer reading the raw map sees the complete picture instead of a curated subset.

Conductor assigns no meaning to any *unreserved* tag. It does not know that `team=platform` routes
a job to a dashboard, only that the tag is there. Interpreting it is the consumer's job — which is
the point: a job needs to carry things like a build number, a ticket reference, or a dashboard URL
that Conductor has no business understanding. The one exception is the small cosmetic vocabulary
the admin dashboard interprets (below).

Tags are **not** inherited by the task or service Conductor creates. `execute()` tags the `runTask`
explicitly with the merged set, and `deploy()` tags the ECS Service explicitly:

- A caller can override any tag on a per-launch basis via `JobRequest.metadata` /
  `DaemonRequest.metadata`. Overrides win over the declared value; a tag the caller doesn't mention
  keeps its declared value; a tag the family never had can be added.
- A caller **cannot** override a *behavioural* `namazu.conductor` key. Those tags drive Conductor's
  own behaviour — the job set, the launch type, the daemon's desired count — so overriding one
  would leave the workload configured one way and managed another. Doing so throws
  `ReservedMetadataKeyException` before any task or service is created. Cosmetic reserved keys are
  free to override; see the admin UI notes in the root README.
- `deploy()` does not set `propagateTags`. The daemon's tags describe the *service*; whether the
  tasks that service runs should inherit them is a separate decision this provider does not make
  on your behalf.

Cosmetic tags the **admin dashboard** interprets (all optional, both forms equivalent on
Kubernetes-style `/` separators):

| Tag | Meaning |
|---|---|
| `namazu.conductor:hidden: "true"` | profile/execution row hidden unless "Show hidden" is checked |
| `namazu.conductor:hidden.{container}: "true"` | just that container's attach row hidden (same toggle reveals it; ECS tasks are single-container, so this is equivalent to `hidden`) |
| `namazu.conductor:agent: "true"` | 🤖 badge; marks a terminal job that is an agent |
| `namazu.conductor:agent.{container}: "true"` | 🤖 badge on that container's attach row (equivalent to `agent` — single container) |
| `namazu.conductor:link.{title}: "https://…"` | clickable pill labelled `{title}`, favicon with 🔗 fallback, ↗ external-link mark, opens in a new tab; repeat per title |

Container qualifiers are independent of the job-level flags and display-only — no provider filters
containers, and `terminal-job` stays pod-level.

`JobExecution.metadata` / `DaemonExecution.metadata` report the tags read back from the task or
service ARN, so they show what actually landed rather than what was requested.

### Required IAM permission

Reading tags back requires `ecs:ListTagsForResource`:

```json
{
  "Effect": "Allow",
  "Action": "ecs:ListTagsForResource",
  "Resource": "*"
}
```

Without it, metadata silently degrades to reporting the requested set instead of the applied one,
and a warning is logged. The bundled integration-test policy grants it unconditionally; AWS does
not document `ecs:cluster` as a supported condition key for this action, and a condition ECS
ignores would defeat the point of reading back at all.

## Network Configuration

VPC network configuration (subnets, security groups, public IP assignment) is applied automatically when the task definition's network mode is `awsvpc`. For EC2 tasks using `bridge` or `host` network mode, no network configuration is applied — the container port maps directly to the host instance.

Once a task reaches `RUNNING`, its container port mappings are surfaced as `JobEndpoint` objects on the returned `JobExecution`. For `awsvpc` tasks the host is the task's ENI address (public or private, depending on `assignPublicIp`). For EC2 `bridge`/`host` tasks the host is the public IP of the container instance, falling back to its private IP.

## Defining Jobs with CloudFormation

Conductor discovers task definitions at runtime by listing all active task definition families in the cluster and filtering by the `namazu.conductor:jobSet` tag. To make a task definition visible to Conductor, add the required tags.

### Fargate task (awsvpc network mode)

The most common configuration. The task runs on Fargate with a public IP so clients can connect directly.

```yaml
MyTaskDefinition:
  Type: AWS::ECS::TaskDefinition
  Properties:
    Family: my-game-server
    NetworkMode: awsvpc
    RequiresCompatibilities:
      - FARGATE
    Cpu: '1024'
    Memory: '2048'
    ExecutionRoleArn: !GetAtt TaskExecutionRole.Arn
    ContainerDefinitions:
      - Name: server
        Image: 123456789012.dkr.ecr.us-east-1.amazonaws.com/my-game-server:latest
        PortMappings:
          - ContainerPort: 7777
            Protocol: udp
    Tags:
      - Key: namazu.conductor:jobSet
        Value: default          # must match the conductor's jobset attribute
      - Key: namazu.conductor:launchType
        Value: FARGATE
      - Key: namazu.conductor:assignPublicIp
        Value: ENABLED          # required for clients to reach the task
```

### EC2 task (bridge network mode)

Use this when you need EC2 instance types not available on Fargate — for example, GPU instances for AI inference or batch workloads. The task runs on a container instance in the cluster with the container port mapped to port 7777 on the host.

```yaml
MyEc2TaskDefinition:
  Type: AWS::ECS::TaskDefinition
  Properties:
    Family: my-gpu-worker
    NetworkMode: bridge
    RequiresCompatibilities:
      - EC2
    ExecutionRoleArn: !GetAtt TaskExecutionRole.Arn
    ContainerDefinitions:
      - Name: worker
        Image: 123456789012.dkr.ecr.us-east-1.amazonaws.com/my-gpu-worker:latest
        Memory: 4096
        PortMappings:
          - ContainerPort: 7777
            HostPort: 7777
            Protocol: tcp
    Tags:
      - Key: namazu.conductor:jobSet
        Value: default
      - Key: namazu.conductor:launchType
        Value: EC2
      # assignPublicIp is only meaningful for awsvpc tasks.
      # For bridge tasks the host IP is used automatically.
```

### Multiple conductor instances on one cluster

Set distinct `jobset` values to partition task definitions between conductor instances. Each conductor will only see task definitions tagged with its own jobset value.

```yaml
# Conductor A sees this task definition
Tags:
  - Key: namazu.conductor:jobSet
    Value: game-sessions

# Conductor B sees this task definition
Tags:
  - Key: namazu.conductor:jobSet
    Value: batch-workers
```

Configure each conductor with the matching attribute:

```
dev.getelements.conductor.ecs.job.set = game-sessions
```

## Stdio Streaming

ECS has no native container stdio API, so `streamStdio(execution)` depends on the task's image
including [`namazu-stdio-bridge`](../stdio-bridge/README.md) — a sidecar wrapper that exposes the
container's stdin/stdout/stderr over WebSocket. To use it:

1. Include the bridge binary in your image and set it as the `ENTRYPOINT` (see
   `stdio-bridge/README.md` for the multi-stage `COPY --from=` pattern).
2. Declare the bridge's port (`10080` by default) in the container's port mappings so it's reachable
   at the task's resolved host (same host `JobEndpoint`s are resolved from — the ENI public/private
   IP for `awsvpc` tasks, or the container instance's EC2 IP otherwise).

`streamStdio` throws `StdioUnavailableException` if the bridge isn't reachable there — which almost
always means the bridge isn't in the image, or its port isn't mapped.

### Authentication

The bridge requires every connection to present a bearer token (see `stdio-bridge/README.md`'s
Authentication section) — this is handled automatically, not something you configure. `execute()`
generates a random per-execution token, injects it into the task's container environment overrides
as `NAMAZU_CONDUCTOR_STDIO_TOKEN`, and carries it on the returned `JobExecution`'s
`EcsExecutionDetails.stdioToken` (`@JsonIgnore`d — it's a secret, not exposed via the REST layer).
`streamStdio` reads it back from there to authenticate.

This means `streamStdio` only works with the `JobExecution` originally returned by `execute()`, or
one derived from it via `getFutureForStatus`/`getStageForStatus` (both carry `details` forward
unchanged) — not an execution reconstructed from `listExecutions()`, which has no way to recover a
token ECS's `describeTasks` never echoes back.

## Integration Test

The module includes an integration test (`EcsOrchestrationServiceIT`) that deploys a full CloudFormation stack (ECS cluster, Fargate task definition, EC2 spot ASG task definition, VPC networking, IAM roles), runs both a Fargate task and an EC2 task, and verifies that each serves the expected HTTP response. The stack is torn down after the suite completes.

The test requires a deployer stack deployed from `cloudformation/integration-test-deployer.yaml` — this creates the ECR repository and a least-privilege IAM user whose credentials drive the stack deploy/destroy cycle.

### Prerequisites

1. Deploy the deployer stack once:
   ```bash
   aws cloudformation deploy \
     --template-file ecs/cloudformation/integration-test-deployer.yaml \
     --stack-name conductor-integration-test-deployer \
     --capabilities CAPABILITY_NAMED_IAM
   ```

2. Build and push the test image:
   ```bash
   AWS_ACCESS_KEY_ID=<deployer-key> AWS_SECRET_ACCESS_KEY=<deployer-secret> \
   AWS_REGION=us-east-1 bash docker/build_docker_ecs.sh
   ```

3. Run the integration tests:
   ```bash
   AWS_ACCESS_KEY_ID=<deployer-key> AWS_SECRET_ACCESS_KEY=<deployer-secret> \
   AWS_REGION=us-east-1 mvn verify -pl ecs
   ```

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `AWS_ACCESS_KEY_ID` | Yes | — | Deployer credentials from the deployer stack outputs |
| `AWS_SECRET_ACCESS_KEY` | Yes | — | Deployer credentials from the deployer stack outputs |
| `AWS_REGION` | Yes | — | AWS region to deploy the test stack into. Test is skipped if absent. |
| `CFN_STACK_NAME` | No | `conductor-integration-test` | Name of the integration test CloudFormation stack |
| `CFN_DEPLOYER_STACK_NAME` | No | `conductor-integration-test-deployer` | Name of the deployer stack, used to resolve the ECR repository URI |
| `CFN_IMAGE_NAME` | No | `conductor-integration-test:latest` | Image name and tag within the ECR repository |
| `CFN_KEEP_STACK` | No | `false` | `true` leaves the stack running after the suite instead of deleting it — see "Faster local iteration" below |

### Faster local iteration

By default the test creates the CloudFormation stack fresh and deletes it in `@AfterClass`, every
single run — several minutes of pure setup/teardown on each `mvn verify -pl ecs`. For a work
session where you're iterating on the `ecs` module, start the stack once and keep it running:

```bash
./ecs/cloudformation/start-integration-test-stack.sh

CFN_KEEP_STACK=true AWS_ACCESS_KEY_ID=<deployer-key> AWS_SECRET_ACCESS_KEY=<deployer-secret> \
AWS_REGION=us-east-1 mvn verify -pl ecs -am   # repeat as many times as you like

./ecs/cloudformation/stop-integration-test-stack.sh   # when you're done for the session
```

`deployStack` already reuses/updates a pre-existing stack (via its `AlreadyExistsException`
handling), so `CFN_KEEP_STACK` only needs to change teardown, not setup. **Leaving the stack running
costs roughly $10-20/month** (one always-on t3.small spot EC2 instance — kept alive by the stack's
Auto Scaling Group — plus its public IPv4 fee; everything else in the stack, including the Fargate
tasks, has no idle cost). Don't forget to stop it when you're done.

The manual **ECS Test Harness** GitHub Actions workflow
(`.github/workflows/ecs-harness.yaml`) is the CI-side equivalent of the two scripts above: a
`workflow_dispatch` with a start/stop choice stands the shared stack up or tears it down using the
workflow's environment credentials (`AWS_PROFILE` left empty). It shares the `conductor-ecs-it`
concurrency group with the IT workflow, so harness actions and test runs never overlap on the same
stack.

### Teardown and the GuardDuty security group

Every teardown path — the test's own `@AfterClass` (`scrubVpcDependencies`), the stop script, and
the harness workflow's stop job — deletes any *unmanaged* security group (not `default`, not owned
by the stack) from the stack's VPC **before** triggering the CloudFormation deletion, waiting first
for the group's attached ENIs to detach. This exists because AWS GuardDuty's EC2 Runtime Monitoring
auto-injects a `GuardDutyManagedSecurityGroup-*` into the VPC whenever an EC2-launch-type task runs;
CloudFormation doesn't own it and can't delete it, and a VPC can't be deleted while a non-default
security group remains — leaving the stack's `Vpc` resource stuck `DELETE_IN_PROGRESS` and the next
run's stack creation failing on a name collision
([#35](https://github.com/NamazuStudios/namazu-conductor/issues/35), which disabled the whole suite
for a while in 2026 until this scrub landed).

### StdioBridgeClientIT (disabled)

A second integration test, `StdioBridgeClientIT`, validates the WebSocket client `streamStdio` uses
against a real `namazu-stdio-bridge` container — independent of any AWS/ECS account, since it talks
to the bridge directly rather than through a task. It's currently disabled
(`@Test(enabled = false)`) — the stdio bridge has no real production consumer yet, and this test's
Docker-container prerequisite was a recurring source of CI/release flakiness. See
https://github.com/NamazuStudios/namazu-conductor/issues/26 to re-enable it.