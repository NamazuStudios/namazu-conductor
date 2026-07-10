# EdgeGap Provider

The `edgegap` module implements `OrchestrationService` for [EdgeGap](https://edgegap.com), a distributed game server hosting platform. Each EdgeGap application version maps to a Conductor job profile. Deployments are submitted via the EdgeGap REST API v1 and Conductor polls for status until the deployment reaches the requested lifecycle state.

## Configuration Attributes

| Attribute | Key | Default | Description |
|---|---|---|---|
| API Key | `dev.getelements.conductor.edgegap.api.key` | _(required)_ | EdgeGap API token. Supplied as `token <value>` in the `Authorization` header. Mark as sensitive in the Elements SDK. |
| Base URL | `dev.getelements.conductor.edgegap.base.url` | `https://api.edgegap.com` | EdgeGap REST API base URL. Override for staging or regional mirrors. |
| Poll Interval | `dev.getelements.conductor.edgegap.poll.interval.ms` | `5000` | Milliseconds between status polls when waiting for a deployment to reach a target state. |
| Stdio Bridge Port | `dev.getelements.conductor.edgegap.stdio.bridge.port` | `10080` | Port a `namazu-stdio-bridge` sidecar (if included in the app version's image) listens on for `streamStdio`. Must be declared in the app version's port mapping to be reachable. |
| Stdio Bridge Base Path | `dev.getelements.conductor.edgegap.stdio.bridge.base.path` | _(none)_ | Must match the bridge's own `NAMAZU_CONDUCTOR_STDIO_URI`. |

The API key is available from the EdgeGap dashboard under **Settings → API Tokens**. Create a token with at least the `deploy` and `status` scopes.

## How Profiles Are Discovered

`getAvailableProfiles()` paginates through all EdgeGap applications in the account (`GET /v1/apps`) and, for each active application, paginates through its versions (`GET /v1/app/{app_name}/versions`). Only **active** apps and **active** versions are included. Every active app/version pair becomes an `EdgeGapJobProfile`.

Profile IDs follow the format `{appName}:{versionName}`, e.g. `my-game:v1.0`.

## Placement Support

EdgeGap supports proximity-based host selection at deployment time. The following `JobPlacement` types are mapped to EdgeGap deploy fields:

| Conductor placement type | EdgeGap field | Behaviour |
|---|---|---|
| `IpPlacement` | `ip_list` | EdgeGap selects the PoP nearest to the supplied IP addresses |
| `LatitudeLongitudePlacement` | `geo_ip_list` | EdgeGap selects the PoP nearest to the supplied coordinates |
| `RegionPlacement` | _(ignored)_ | EdgeGap v1 has no named-region concept; silently dropped |

When multiple placement entries are supplied they are all forwarded and EdgeGap picks the best-matching PoP across all of them. Omitting placement entirely lets EdgeGap apply its default selection logic.

## How Endpoints Are Mapped

Once a deployment reaches `RUNNING`, Conductor reads the `ports` map from `GET /v1/status/{request_id}` and converts each entry to a `JobEndpoint`:

| EdgeGap field | `JobEndpoint` field | Notes |
|---|---|---|
| `fqdn` (preferred) or `public_ip` | `host` | The stable FQDN is used when available; falls back to the raw public IP |
| `external` | `port` | The externally reachable port number assigned by EdgeGap |
| `protocol` | `protocol` | e.g. `"UDP"`, `"TCP"` |

Each named port in EdgeGap's port configuration produces one `JobEndpoint`. If a deployment exposes both a game port and a metrics port they will both appear in `JobExecution.endpoints`.

## Lifecycle Status Mapping

EdgeGap deployment statuses are mapped to `JobStatus` as follows:

| EdgeGap status (suffix) | `JobStatus` |
|---|---|
| `INITIALIZING`, `WAITING` | `PENDING` |
| `RUNNING` | `RUNNING` |
| `TERMINATED`, `TERMINATING` | `COMPLETED` |
| anything else | `FAILED` |

## Setting Up an Application in EdgeGap

Before Conductor can deploy a job, the application and at least one version must exist in the EdgeGap platform and be marked **active**.

1. **Create an application** in the EdgeGap dashboard or via the API. The application name becomes part of the profile ID.

2. **Create a version** under the application. Configure:
   - **Container image** — the image to run (ECR, Docker Hub, etc.)
   - **Port mappings** — declare every port the container exposes, including the protocol. These become the `JobEndpoint` entries Conductor returns.
   - **Environment variables** — any variables that should be set at the platform level (Conductor can also inject per-deployment overrides at runtime via `JobRequest.environment`).

3. **Set the version to active.** Inactive versions are excluded from `getAvailableProfiles()`.

### Port configuration example

For a game server that exposes a UDP game port and a TCP HTTP status port:

| Port name | Internal | Protocol |
|---|---|---|
| `game` | 7777 | UDP |
| `status` | 8080 | TCP |

EdgeGap assigns external port numbers at deployment time. Conductor maps both ports to `JobEndpoint` objects; clients should select the endpoint matching their required protocol.

### Passing environment variables at runtime

`JobRequest.environment` is forwarded to EdgeGap as `env_vars` in the deploy request. These override or augment the variables defined on the app version:

```kotlin
service.execute(
    JobRequest(
        profile = profile,
        environment = mapOf(
            "SERVER_MAP" to "de_dust2",
            "MAX_PLAYERS" to "16"
        )
    )
)
```

### Passing a custom command

`JobRequest.command` and `JobRequest.args` are concatenated (command first, then args) and sent as the `command` field in the EdgeGap deploy request, overriding the container's default entrypoint command.

## Stdio Streaming

EdgeGap has no native container stdio API, so `streamStdio(execution)` depends on the app version's
image including [`namazu-stdio-bridge`](../stdio-bridge/README.md) — a sidecar wrapper that exposes
the container's stdin/stdout/stderr over WebSocket. To use it:

1. Include the bridge binary in your image and set it as the `ENTRYPOINT` (see
   `stdio-bridge/README.md` for the multi-stage `COPY --from=` pattern).
2. Declare the bridge's port (`10080` by default) in the app version's port mapping so it's
   reachable at the deployment's `fqdn`/`public_ip`.

`streamStdio` connects to the same host `JobEndpoint`s are resolved from, on the configured
`Stdio Bridge Port`, and throws `StdioUnavailableException` if the bridge isn't reachable there —
which almost always means the bridge isn't in the image, or its port isn't mapped.

### Authentication

The bridge requires every connection to present a bearer token (see `stdio-bridge/README.md`'s
Authentication section) — this is handled automatically, not something you configure. `execute()`
generates a random per-execution token, injects it into the deployment's environment as
`NAMAZU_CONDUCTOR_STDIO_TOKEN`, and carries it on the returned `JobExecution`'s
`EdgeGapExecutionDetails.stdioToken` (`@JsonIgnore`d — it's a secret, not exposed via the REST
layer). `streamStdio` reads it back from there to authenticate.

This means `streamStdio` only works with the `JobExecution` originally returned by `execute()`, or
one derived from it via `getFutureForStatus`/`getStageForStatus` (both carry `details` forward
unchanged) — not an execution reconstructed from `listExecutions()`, which has no way to recover a
token EdgeGap's API never echoes back.

## Integration Test

The module includes an integration test (`EdgeGapOrchestrationServiceIT`) that deploys a real EdgeGap application, waits for it to reach `RUNNING`, and verifies the exposed HTTP endpoint returns the expected JSON response.

### Prerequisites

1. An EdgeGap account with an application named `integration_test` containing a version named `nginx`. The version must be active and use the `nginx_it` Docker image from this repository (see `docker/`).

2. Configure the port on the version:
   - Port name: `http` (or any name)
   - Internal port: `80`
   - Protocol: `TCP`

3. Set the `EDGEGAP_API_KEY` environment variable:
   ```bash
   EDGEGAP_API_KEY=<your-api-key> mvn verify -pl edgegap
   ```
   The `token ` prefix is optional — the test strips it if present.

The test is skipped automatically if `EDGEGAP_API_KEY` is absent.

### What the test does

1. Looks up the public IP of the test runner machine and passes it as an `IpPlacement` so EdgeGap picks the nearest PoP.
2. Deploys `integration_test:nginx` with a fixed set of environment variables.
3. Polls `getFutureForStatus(..., RUNNING)` for up to 10 minutes.
4. Makes an HTTP GET to `http://{host}:{port}/test_context.json` and asserts:
   - HTTP 200
   - `args` is empty
   - `environment` contains exactly the variables that were passed in

The deployment is stopped in `@AfterClass` regardless of test outcome.

### StdioBridgeClientIT

A second integration test, `StdioBridgeClientIT`, validates the WebSocket client `streamStdio` uses
against a real `namazu-stdio-bridge` container — independent of any EdgeGap account, since it talks
to the bridge directly rather than through a deployment. Unlike `EdgeGapOrchestrationServiceIT`,
this test does **not** skip when its prerequisite is missing — it fails, since a reachable bridge is
expected to already be running (this test does not provision one itself). Start one locally before
`mvn verify -pl edgegap`:

```bash
docker build -t namazu-stdio-bridge:it ../stdio-bridge
docker run -d --rm -p 10080:10080 \
  -v "$(pwd)/src/test/resources/stdio-bridge-toy-entrypoint.sh:/toy-entrypoint.sh:ro" \
  -e NAMAZU_CONDUCTOR_STDIO_ENTRYPOINT=/toy-entrypoint.sh \
  -e NAMAZU_CONDUCTOR_STDIO_TOKEN=test-token \
  namazu-stdio-bridge:it
```

The container exits after the test sends its `"quit"` line, so it must be restarted before each run.
Override `STDIO_BRIDGE_IT_HOST`/`STDIO_BRIDGE_IT_PORT`/`STDIO_BRIDGE_IT_TOKEN` (default
`localhost`/`10080`/`test-token`, matching the command above) to point at a differently-configured
bridge.