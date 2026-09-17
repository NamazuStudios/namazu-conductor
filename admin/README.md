# Admin UI

The `admin` module provides a superuser-only dashboard page and a matching REST endpoint that lets operators inspect the state of every Conductor provider deployed in a running Elements instance. It is a self-contained `.elm` archive with no provider-specific dependencies — deploy it alongside whichever provider elements you are using.

## What it does

- **Dashboard page** — appears in the Elements dashboard under **Conductor** (Layers icon) in the superuser sidebar. Shows a green / yellow / red status indicator and a full table of job profiles for each deployed provider, including all provider-specific metadata fields.
- **REST endpoint** — `GET /conductor/admin-console/rest/profiles` aggregates profile data from every deployed `OrchestrationService` at request time. No static provider configuration is required; new providers are picked up automatically.

## Deployment

Add the admin element to your deployment alongside whichever provider elements you want to monitor:

```kotlin
builder
    .elementPackage()
    .elmArtifact("dev.getelements.conductor:ecs:elm:${version}")
    .endElementPackage()
    .elementPackage()
    .elmArtifact("dev.getelements.conductor:admin:elm:${version}")
    .endElementPackage()
```

The admin element has no required configuration. Both attributes have sensible defaults.

## Configuration

| Attribute | Key | Default | Description |
|---|---|---|---|
| Auth enabled | `dev.getelements.elements.auth.enabled` | `true` | Enables the Elements auth filter. Set to `false` only in isolated development environments. |
| REST root | `dev.getelements.elements.element.rs.root` | `/conductor/admin-console/rest` | Base path for the JAX-RS application. Change this only if another element already occupies that path. |
| WebSocket root | `dev.getelements.elements.element.ws.root` | `/conductor/admin-console/ws` | Base path for the terminal WebSocket endpoints. Must stay distinct from the REST root (see the field doc on `WS_ROOT` in `ConductorAdminApplication.kt`). |
| UI content root | `dev.getelements.element.ui.uri` | `/app/ui/conductor-admin-console` | Base path the dashboard UI plugin bundle is served from. Deliberately kept under `/app/ui/` — the dashboard's plugin loader only discovers UI content whose URI contains that literal substring, so anything else silently never appears in the sidebar (see [NamazuStudios/elements#102](https://github.com/NamazuStudios/elements/issues/102)). |

You can safely override any of these per-deployment — the dashboard UI discovers its own REST/WS roots at runtime (via the platform's `GET /api/rest/elements/system`, which reports each element's live, resolved attributes) rather than assuming the compiled-in defaults, so it doesn't need a matching frontend rebuild.

## REST API

### `GET /conductor/admin-console/rest/profiles`

Returns the current profile list from every deployed `OrchestrationService` provider.

**Authentication:** `Elements-SessionSecret` header with a valid session token. `SUPERUSER` level required — returns `403` for any other level.

#### Response

```json
{
  "status": "ok",
  "providers": [
    {
      "element": "dev.getelements.conductor.ecs",
      "providerType": "EcsJobProfile",
      "profiles": [
        {
          "id": "my-game-server",
          "family": "my-game-server",
          "launchType": "FARGATE"
        }
      ],
      "error": null
    }
  ]
}
```

| `status` | Meaning |
|---|---|
| `ok` | All providers returned profiles without error |
| `partial` | At least one provider succeeded; at least one failed |
| `error` | All providers failed, or none are deployed |

#### Error responses

| HTTP status | Cause |
|---|---|
| `403 Forbidden` | Not authenticated, or user is not `SUPERUSER` |
| `503 Service Unavailable` | No `OrchestrationService` providers are deployed |

## Dashboard status indicator

| Indicator | Meaning |
|---|---|
| Green — Ready | All deployed providers responded successfully |
| Yellow — Partial | At least one provider responded; others returned errors |
| Red — Unavailable | No providers deployed, or all returned errors |

Errors are expandable — click the error badge on any provider row to see the full message.

## Multi-provider behaviour

The admin element queries the Elements registry at request time. If multiple Conductor providers are deployed (e.g. both ECS and Kubernetes), each appears as a separate entry in the `providers` array. Providers that fail to return profiles report their error inline without affecting the others.