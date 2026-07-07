# Kubernetes Provider

The `kubernetes` module implements `OrchestrationService` for Kubernetes using the [Fabric8](https://github.com/fabric8io/kubernetes-client) client. A `JobProfile` maps to a Kubernetes **`PodTemplate`** resource; behavior is driven by labels and annotations on the template rather than service-level configuration, so each template declares its own runtime requirements.

A profile can run as either a long-standing **`Pod`** or a one-off **`batch/v1 Job`**, and a **`Service`** is created on demand when the template asks for ports to be exposed.

## Configuration Attributes

| Attribute | Key | Default | Description |
|---|---|---|---|
| Namespace | `dev.getelements.conductor.kubernetes.namespace` | `default` | Namespace in which templates are discovered and workloads created |
| Jobset | `dev.getelements.conductor.kubernetes.job.set` | `default` | Only templates labelled `namazu.conductor/job-set` matching this value are surfaced as profiles |
| Kubeconfig path | `dev.getelements.conductor.kubernetes.kubeconfig.path` | _(auto-detect)_ | Optional path to a kubeconfig file. When empty, Fabric8 auto-detects (in-cluster service account, then `~/.kube/config`) |
| Master URL | `dev.getelements.conductor.kubernetes.master.url` | _(from config)_ | Optional API server URL override |
| Poll interval | `dev.getelements.conductor.kubernetes.poll.interval.ms` | `5000` | Interval at which workload status is polled while awaiting a target status |
| Watch enabled | `dev.getelements.conductor.kubernetes.watch.enabled` | `false` | When `true`, workload status transitions are observed via a Kubernetes watch on the underlying Pod/Job instead of polling every `poll.interval.ms`; falls back to polling if the watch closes with an error before the target status is reached |

## PodTemplate Labels & Annotations

### `namazu.conductor/job-set` (label)

**Required.** Identifies which conductor instance owns this template. Only templates whose `namazu.conductor/job-set` label matches the conductor's configured `jobset` attribute are returned by `getAvailableProfiles()`, so multiple conductor instances can share a cluster without seeing each other's templates.

```yaml
metadata:
  labels:
    namazu.conductor/job-set: default
```

### `namazu.conductor/workload-kind` (annotation)

Selects the workload primitive created at execution time.

| Value | Behaviour |
|---|---|
| `pod` | A long-standing bare `Pod`. Pod phase maps directly onto `JobStatus`. **Default** if the annotation is absent. |
| `job` | A one-off `batch/v1 Job` (run-to-completion). Job status drives `COMPLETED`/`FAILED`; while active, the underlying pod supplies `RUNNING` and endpoints. |

```yaml
metadata:
  annotations:
    namazu.conductor/workload-kind: pod
```

### `namazu.conductor/expose-ports` (annotation)

A comma-separated list of `port[/protocol]` entries (protocol defaults to `TCP`). When present, a `Service` selecting the workload is created; when **absent, no Service is created** and endpoints fall back to the pod IP.

```yaml
metadata:
  annotations:
    namazu.conductor/expose-ports: "7777/udp,8080/tcp"
```

### `namazu.conductor/service-type` (annotation)

The type of the Service created for exposed ports. Defaults to `NodePort`.

| Value | Endpoint host resolution |
|---|---|
| `NodePort` | A Ready node's `ExternalIP` (falling back to `InternalIP`) + the allocated node port. **Default.** |
| `LoadBalancer` | The Service's `status.loadBalancer.ingress` IP/hostname + the service port. |
| `ClusterIP` | The Service's cluster IP + the service port (reachable only inside the cluster). |

```yaml
metadata:
  annotations:
    namazu.conductor/service-type: LoadBalancer
```

## Endpoints, Services & Placement

When a template declares `expose-ports`, `execute()` creates the workload and a `Service` of the requested type. The Service is labelled `namazu.conductor/owned-by=<workload-name>`, which lets `stop()` delete both the workload and its Service without persisting any state. When no ports are exposed, endpoints are resolved directly from the pod IP and its container ports (or empty for a port-less one-off Job).

Command, argument, and environment overrides from the `JobRequest` are applied to the template's primary (first) container — `command` maps to the container's `command`, `args` to `args`, and `environment` is merged over the container's env.

Only `RegionPlacement` is honoured, mapped to a `topology.kubernetes.io/zone` node selector (zone is finer-grained than region; its `id` is the target zone). `IpPlacement` and `LatitudeLongitudePlacement` are silently ignored.

## Defining Jobs with PodTemplates

Conductor discovers templates at runtime by listing `PodTemplate`s in the configured namespace and filtering by the `namazu.conductor/job-set` label. To make a template visible, apply it with the required label.

### Long-standing server exposed via LoadBalancer

```yaml
apiVersion: v1
kind: PodTemplate
metadata:
  name: my-game-server
  namespace: default
  labels:
    namazu.conductor/job-set: default          # must match the conductor's jobset attribute
  annotations:
    namazu.conductor/workload-kind: pod
    namazu.conductor/expose-ports: "7777/udp"
    namazu.conductor/service-type: LoadBalancer
template:
  spec:
    containers:
      - name: server
        image: ghcr.io/example/my-game-server:latest
        ports:
          - containerPort: 7777
            protocol: UDP
```

### One-off batch job (no exposed ports)

```yaml
apiVersion: v1
kind: PodTemplate
metadata:
  name: my-batch-worker
  namespace: default
  labels:
    namazu.conductor/job-set: default
  annotations:
    namazu.conductor/workload-kind: job
template:
  spec:
    restartPolicy: Never
    containers:
      - name: worker
        image: ghcr.io/example/my-batch-worker:latest
        # ttlSecondsAfterFinished on the Job is the operator's concern; set it on the template
```

### Multiple conductor instances on one cluster

Set distinct `jobset` values to partition templates between conductor instances:

```yaml
# Conductor A sees this template
metadata:
  labels:
    namazu.conductor/job-set: game-sessions

# Conductor B sees this template
metadata:
  labels:
    namazu.conductor/job-set: batch-workers
```

Configure each conductor with the matching attribute:

```
dev.getelements.conductor.kubernetes.job.set = game-sessions
```

## Integration Test

The module includes an integration test (`KubernetesOrchestrationServiceIT`) that runs against a real cluster — **minikube** locally and in GitHub CI. It creates its own `PodTemplate`s (a `NodePort` server, a `LoadBalancer` server, and a one-off `Job`), exercises discovery, `execute()`, status polling (or watching, via `KUBERNETES_IT_WATCH_ENABLED`), endpoint resolution, and `stop()`, then deletes everything it created.

**The test does not start or provision a cluster — one must already be running before `mvn verify`** (minikube locally; provisioned by the CI workflow in GitHub). The suite **always runs** and never skips: with no reachable cluster the calls fail and the suite fails. Every pipeline that reaches the `verify` phase therefore needs a reachable cluster, which is why the publish workflows provision minikube too.

### Running locally with minikube

A helper script starts minikube and the tunnel in one step. Leave it running, then run the tests in another terminal:

```bash
# Terminal 1 — starts minikube, then runs `minikube tunnel` in the foreground (prompts for sudo once).
./kubernetes/start-minikube.sh

# Terminal 2 — re-run as often as you like while the script stays up.
mvn verify -pl kubernetes -am
```

Or do it by hand:

```bash
minikube start
minikube tunnel        # second terminal — assigns external IPs to LoadBalancer Services (one sudo auth per session)
mvn verify -pl kubernetes -am
```

To skip the LoadBalancer path (no tunnel/sudo), start the cluster only and disable the HTTP check:

```bash
./kubernetes/start-minikube.sh --no-tunnel
KUBERNETES_IT_HTTP_CHECK=false mvn verify -pl kubernetes -am
```

Connectivity uses Fabric8 auto-detection of `~/.kube/config` (written by `minikube start`). The test namespace defaults to `conductor-it` and is created and destroyed by the test. If you only want to exercise the non-LoadBalancer paths (no tunnel), set `KUBERNETES_IT_HTTP_CHECK=false` — the `NodePort` endpoint is reachable via the minikube node IP without a tunnel.

> **`minikube tunnel` and sudo:** on Linux, `minikube tunnel` adds a route to the service network and so requires `sudo` (a one-time authentication per session), regardless of the service port. GitHub runners have passwordless sudo, so the CI workflow runs it non-interactively.

### Continuous integration

`.github/workflows/kubernetes-it.yaml` provisions minikube (`medyagh/setup-minikube`, docker driver), starts `minikube tunnel`, and runs `mvn verify -pl kubernetes -am` on pull requests and on demand. The `snapshot-publish` and `release` workflows do the same before their `deploy`, since `deploy` runs the always-on integration test.

### Environment variables

All are optional; defaults target a minikube run.

| Variable | Default | Description |
|---|---|---|
| `KUBERNETES_IT_NAMESPACE` | `conductor-it` | Namespace for templates/workloads; created if absent |
| `KUBERNETES_IT_JOBSET` | `default` | Value for the `namazu.conductor/job-set` label/filter |
| `KUBERNETES_IT_KUBECONFIG` | _(auto-detect)_ | Path to a kubeconfig file |
| `KUBERNETES_IT_CONTEXT` | _(current-context)_ | kubeconfig context name |
| `KUBERNETES_IT_MASTER_URL` | _(from config)_ | API server URL override |
| `KUBERNETES_IT_POD_IMAGE` | `hashicorp/http-echo` | Image for the server pod tests (serves on 8080) |
| `KUBERNETES_IT_POD_PORT` | `8080` | Container port exposed by the server pod tests (non-privileged) |
| `KUBERNETES_IT_POD_ARGS` | `-listen=:8080,-text=conductor-ok` | Comma-separated container args |
| `KUBERNETES_IT_POD_PROTOCOL` | `tcp` | Protocol for the exposed port |
| `KUBERNETES_IT_HTTP_CHECK` | `true` | If `true`, HTTP GET the resolved endpoint and assert a response |
| `KUBERNETES_IT_HTTP_PATH` | `/` | Path used by the HTTP check |
| `KUBERNETES_IT_JOB_IMAGE` | `busybox:stable` | Image for the one-off job test |
| `KUBERNETES_IT_JOB_COMMAND` | `sh,-c,echo hello-from-conductor` | Comma-separated command for the job test |
| `KUBERNETES_IT_TIMEOUT_MINUTES` | `5` | Per-status / endpoint-resolution wait timeout |
| `KUBERNETES_IT_WATCH_ENABLED` | `false` | Exercises the watch-based status path (`WATCH_ENABLED` attribute) instead of polling |