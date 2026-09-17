# Admin UI

## Overview

The `admin` module ships a superuser-only dashboard page and a matching REST endpoint that lets operators inspect the state of every Conductor provider deployed in a running Elements instance. It is a self-contained `.elm` archive that you deploy alongside whichever provider elements (ECS, EdgeGap, Kubernetes) you are using.

The page shows a **green / yellow / red status indicator** at a glance, then lists every deployed provider by element name together with a full table of its available job profiles and all provider-specific metadata fields.

---

## Deployment

Add the admin element to your deployment alongside whichever provider elements you want to monitor:

```kotlin
builder
    .elementPackage()
    .elmArtifact("dev.getelements.conductor:ecs:elm:${version}")
    .elmArtifact("dev.getelements.conductor:edgegap:elm:${version}")
    .elmArtifact("dev.getelements.conductor:kubernetes:elm:${version}")
    .elmArtifact("dev.getelements.conductor:admin:elm:${version}")
    .endElementPackage()
```

The admin element has no required configuration. Authentication is enabled by default.

---

## Dashboard UI

Once deployed, the page appears in the Elements dashboard under **Conductor** (Layers icon) in the superuser navigation. It is visible only to users with `SUPERUSER` level.

### Status indicator

| Indicator | Meaning |
|---|---|
| Green — Ready | All deployed providers responded successfully |
| Yellow — Partial | At least one provider responded; others returned errors |
| Red — Unavailable | No providers are deployed, or all returned errors |

### Profile table

Each provider is listed by its fully-qualified element name (e.g. `dev.getelements.conductor.ecs`). Below it, a table renders every available `JobProfile` with all fields exposed — including provider-specific metadata such as launch type, network mode, app/version names, workload kind, and port configuration. Fields absent for a given profile are shown as `—`.

---

## REST API

The admin element exposes a single endpoint for programmatic access.

### `GET /conductor/admin-console/rest/profiles`

Returns the current profile list from every deployed `OrchestrationService` provider.

**Authentication:** Required. Pass the session secret via the `session_secret` header.

**Authorization:** `SUPERUSER` level required. Returns `403 Forbidden` for authenticated users below that level.

#### Success response — all providers healthy

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
          "containerName": "game",
          "launchType": "FARGATE",
          "networkMode": "awsvpc",
          "assignPublicIp": "ENABLED"
        }
      ],
      "error": null
    }
  ]
}
```

#### Partial response — one provider errored

```json
{
  "status": "partial",
  "providers": [
    {
      "element": "dev.getelements.conductor.ecs",
      "providerType": "EcsJobProfile",
      "profiles": [ ... ],
      "error": null
    },
    {
      "element": "dev.getelements.conductor.edgegap",
      "providerType": null,
      "profiles": null,
      "error": "Connection refused"
    }
  ]
}
```

#### Status values

| `status` | Meaning |
|---|---|
| `ok` | All providers returned profiles without error |
| `partial` | At least one provider succeeded; at least one failed |
| `error` | All providers failed, or no providers are deployed |

#### Error responses

| HTTP status | Cause |
|---|---|
| `401 Unauthorized` | No session / auth service unavailable |
| `403 Forbidden` | Authenticated user is not `SUPERUSER` |
| `503 Service Unavailable` | No `OrchestrationService` providers are deployed |

---

## Configuration

The admin element exposes several attributes. All have defaults and do not normally need to be overridden.

| Attribute | Key | Default | Description |
|---|---|---|---|
| Auth enabled | `dev.getelements.elements.auth.enabled` | `true` | Enables the Elements auth filter. Set to `false` only in isolated development environments. |
| REST root | `dev.getelements.elements.element.rs.root` | `/conductor/admin-console/rest` | Base path for the JAX-RS application. Change this if another element already occupies that path. |
| WebSocket root | `dev.getelements.elements.element.ws.root` | `/conductor/admin-console/ws` | Base path for the terminal WebSocket endpoints. |
| UI content root | `dev.getelements.element.ui.uri` | `/app/ui/conductor-admin-console` | Base path the dashboard UI plugin bundle is served from. Deliberately kept under `/app/ui/` — the dashboard's plugin loader only discovers UI content whose URI contains that literal substring, so anything else silently never appears in the sidebar (see [NamazuStudios/elements#102](https://github.com/NamazuStudios/elements/issues/102)). |

These are safe to override per-deployment: the dashboard UI discovers its own REST/WS roots at runtime rather than assuming the compiled-in defaults, so an override doesn't require rebuilding the frontend.

---

## Multiple Providers

The admin element queries every element in the deployment registry at request time. If you deploy multiple Conductor providers (e.g. both ECS and Kubernetes), each appears as a separate entry in the `providers` array with its own profile list. There is no static configuration required — new providers are picked up automatically on the next request.

**This does not extend to multiple deployments of the *same* provider** (e.g. two `kubernetes` deployments differentiated only by job set) — the admin element identifies each provider by its static package name, which is identical across such deployments, so they collapse into one entry instead of appearing separately. See the "Multiple conductor instances on one cluster" caveat in `kubernetes/README.md` for why (blocked on [NamazuStudios/elements#99](https://github.com/NamazuStudios/elements/issues/99)).