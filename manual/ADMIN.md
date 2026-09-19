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

### `GET /conductor/admin/profiles`

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

## WebSocket API

Terminal sessions attach over WebSocket under the configured WS root (see the WebSocket root row in
Configuration below):

| Endpoint | Purpose |
|---|---|
| `/conductor/ws/service/{jobId}` | Attaches to the job's primary container |
| `/conductor/ws/service/{jobId}/{containerId}` | Attaches to a specific non-primary container |

**Handshake:** the client connects with no query parameters — browsers can't set an `Authorization`
header on the native `WebSocket()` constructor, so authorization instead happens over the connection
itself. The very first WebSocket **text** frame the client sends must be a JSON payload:

```json
{
  "sessionSecret": "<a valid Elements session secret>",
  "command": ["optional", "override", "argv"]
}
```

`command` is optional; when omitted, the provider execs its own default (typically a shell). The
server closes the connection with close code `VIOLATED_POLICY` if this frame doesn't arrive within 10
seconds of connecting, is malformed, carries an invalid or expired session secret, or belongs to a
user below `SUPERUSER` level. `UNEXPECTED_CONDITION` is used instead if authorization succeeds but the
provider fails to open stdio for the job/container.

**Steady-state framing**, once authorized:

| Frame type | Direction | Contents |
|---|---|---|
| Binary | Both | Raw stdio bytes — keystrokes client→server, pty output server→client |
| Text | Client→server only | Resize control message: `{"type":"resize","cols":N,"rows":N}` |

### Toast notifications (bell-adjacent)

A job/container can surface a toast in the operator's dashboard (in addition to, or instead of, an
audible bell) by writing a custom OSC (Operating System Command) escape sequence to its own stdout:

```
\x1b]9001;<message>\x07
```

e.g. from a shell: `printf '\e]9001;Build finished\a'`. `<message>` is free text (avoid embedding a
literal `BEL`/`0x07`, which terminates the sequence, or `ESC`, which starts a new one). Any `http(s)`
URL appearing anywhere in the message is rendered as a real clickable link.

This travels in-band over the existing binary pty stream — there is no separate WebSocket frame type
for it, and no server-side involvement — the dashboard's terminal (xterm.js) parses the OSC sequence
client-side using the same parser that already recognizes the plain `BEL` control character for the
audible bell. `9001` is an arbitrary, unassigned OSC number chosen to avoid colliding with established
conventions (OSC 9 iTerm2 growl, OSC 777 konsole/xterm notify, OSC 1337 iTerm2 proprietary). See
[#37](https://github.com/NamazuStudios/namazu-conductor/issues/37). Each toast plays a short chime
(independent of, and in addition to, the terminal bell sound) at the same volume/mute setting.

The dashboard keeps a ring buffer of the last 200 toasts (shared across a session's terminal tabs,
alongside bell volume), shown via a 🍞 history menu next to the existing bell volume/mute controls,
plus a transient popup per toast that the operator can dismiss individually or let auto-expire. Both
the transient popup and each entry in the history menu carry their own `✕` — dismissing one doesn't
touch the rest.

### Open URL (OSC 1337)

A job/container can ask the operator's browser to open a URL — a generated report, a status page, an
OAuth-style consent screen, etc. — the same way, over the same in-band pty stream:

```
\x1b]1337;OpenURL=<url>\x07
```

e.g. from a shell: `printf '\033]1337;OpenURL=%s\007' "https://example.com"`. Unlike the toast's `9001`
(deliberately an arbitrary, previously-unclaimed number), this reuses iTerm2's existing proprietary
`1337` sequence and its `OpenURL=` subcommand on purpose — a terminal that doesn't recognize a given
OSC sequence just silently ignores it, so piggybacking on an already-established one here is safe, and
avoids inventing a new protocol. See [#34](https://github.com/NamazuStudios/namazu-conductor/issues/34).
Only `http`/`https` URLs are ever acted on — anything else, including a malformed URL, is silently
dropped.

The dashboard attempts `window.open()` immediately — there's no confirmation step — but that's not
guaranteed to succeed: browsers only let `window.open()` escape the popup blocker when it's the direct
result of a user gesture (a click), and this fires from an async WebSocket message, so it can get
silently blocked. Either way, it also drops a toast carrying an **Open ↗** button, since a real click
on that button always works regardless of the popup blocker — that's the reliable path, not the
automatic attempt.

### Clipboard (OSC 52)

A job/container can copy text to the *operator's* clipboard (not the container's) using the
de-facto-standard clipboard OSC that `tmux`, `vim`, and many other tools already emit:

```
\x1b]52;c;<base64>\x07
```

e.g. from a shell: `printf '\033]52;c;%s\007' "$(printf 'hello' | base64)"`. The clipboard selector
(`c` above) is ignored — there's only ever one target, the operator's browser clipboard. A `?` payload
(a read-back query) is ignored too, since this is a write-only relay.

xterm.js has no built-in handler for OSC 52 — it's parsed the same way toasts and Open URL are, as a
custom handler registered on the terminal instance. Same caveat as Open URL: the dashboard attempts
`navigator.clipboard.writeText()` immediately, which can get rejected without a user gesture
(especially in Firefox), so it also drops a toast with a **Copy** button as the reliable fallback.

---

## Configuration

The admin element exposes several attributes. All have defaults and do not normally need to be overridden.

| Attribute | Key | Default | Description |
|---|---|---|---|
| Auth enabled | `dev.getelements.elements.auth.enabled` | `true` | Enables the Elements auth filter. Set to `false` only in isolated development environments. |
| REST root | `dev.getelements.elements.element.rs.root` | `/conductor/admin` | Base path for the JAX-RS application. The original 1.1 path, restored for backward compatibility. Change this only if another element already occupies that path, or if your `http.path.prefix` is `/conductor` (see [NamazuStudios/namazu-conductor#24](https://github.com/NamazuStudios/namazu-conductor/issues/24)). |
| WebSocket root | `dev.getelements.elements.element.ws.root` | `/conductor/ws` | Base path for the terminal WebSocket endpoints. |
| UI content root | `dev.getelements.element.ui.uri` | *(no override)* | Base path the dashboard UI plugin bundle is served from. No override needed — the platform's implicit `/app/ui/{package name}` default already satisfies the dashboard's plugin loader (see [NamazuStudios/elements#102](https://github.com/NamazuStudios/elements/issues/102)) for a normal single-deployment install. |

These are safe to override per-deployment: the dashboard UI discovers its own REST/WS roots at runtime rather than assuming the compiled-in defaults, so an override doesn't require rebuilding the frontend.

---

## Multiple Providers

The admin element queries every element in the deployment registry at request time. If you deploy multiple Conductor providers (e.g. both ECS and Kubernetes), each appears as a separate entry in the `providers` array with its own profile list. There is no static configuration required — new providers are picked up automatically on the next request.

**This does not extend to multiple deployments of the *same* provider** (e.g. two `kubernetes` deployments differentiated only by job set) — the second one can't even be staged if it's packaged into the same deployment as the first ([NamazuStudios/elements#103](https://github.com/NamazuStudios/elements/issues/103)), and even across separate deployments the admin element identifies each provider by its static package name, which is identical across such deployments, so they collapse into one entry instead of appearing separately ([NamazuStudios/elements#99](https://github.com/NamazuStudios/elements/issues/99)). See the "Multiple conductor instances on one cluster" caveat in `kubernetes/README.md` for details.