# ECS Provider

The `ecs` module implements `OrchestrationService` for AWS ECS, supporting both Fargate and EC2 launch types. Behavior is driven by tags on ECS task definitions rather than service-level configuration, so each task definition declares its own runtime requirements.

## Configuration Attributes

| Attribute | Key | Default | Description |
|---|---|---|---|
| Cluster | `dev.getelements.conductor.ecs.cluster` | _(required)_ | Short name or ARN of the ECS cluster |
| Subnets | `dev.getelements.conductor.ecs.subnets` | _(required for `awsvpc`)_ | Comma-separated VPC subnet IDs |
| Security Groups | `dev.getelements.conductor.ecs.security.groups` | _(required for `awsvpc`)_ | Comma-separated security group IDs |
| Jobset | `dev.getelements.conductor.ecs.jobset` | `default` | Only task definitions tagged with `namazu.conductor:jobSet` matching this value are surfaced as profiles |

## Task Definition Tags

All tags use the `namazu.conductor:` prefix. They are set on the task definition in the AWS Console, via the AWS CLI, or in your infrastructure-as-code (Terraform, CDK, CloudFormation).

### `namazu.conductor:jobSet`

**Required.** Identifies which conductor instance owns this task definition. Only task definitions whose `namazu.conductor:jobSet` value matches the conductor's configured `jobset` attribute are returned by `getAvailableProfiles()`. This prevents multiple conductor instances sharing a cluster from seeing each other's task definitions.

```
namazu.conductor:jobSet = default
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
dev.getelements.conductor.ecs.jobset = game-sessions
```

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