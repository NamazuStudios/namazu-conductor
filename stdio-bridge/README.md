# namazu-stdio-bridge

A lightweight wrapper (similar to `dockerize`) for containers on platforms without native stdio
access (EdgeGap, ECS). It discovers and execs the container's real entrypoint, forwards all `argv`
transparently, and exposes that process's stdin/stdout/stderr over three WebSocket endpoints — the
same bidirectional access namazu-conductor's Kubernetes provider gets natively via `kubectl attach`
(see `OrchestrationService.streamStdio` / `JobStdio`).

## Configuration

All optional, via environment variables:

| Variable | Default | Description |
|---|---|---|
| `NAMAZU_CONDUCTOR_STDIO_TOKEN` | _(required)_ | Bearer token every WebSocket connection must present (see Authentication below). The bridge refuses to start without it. |
| `NAMAZU_CONDUCTOR_STDIO_URI` | `/` | Base path prefix for the WebSocket endpoints |
| `NAMAZU_CONDUCTOR_STDIO_ENTRYPOINT` | `/docker-entrypoint.sh:/usr/local/bin/docker-entrypoint.sh:/app/docker-entrypoint.sh` | `os.pathsep`-separated list of candidate entrypoint paths, tried in order; the first existing, executable path wins |
| `NAMAZU_CONDUCTOR_STDIO_BUFFER_SIZE` | `4096` | Chunk size in bytes per WebSocket message (distinct from the replay buffers below) |
| `NAMAZU_CONDUCTOR_STDIO_STDOUT_BUFFER_SIZE` | `16k` | Ring buffer capacity for stdout replay (see Wire Protocol below). Accepts a plain byte count or a `k`/`m` suffix (KiB/MiB). `0` disables replay. |
| `NAMAZU_CONDUCTOR_STDIO_STDERR_BUFFER_SIZE` | `4096` | Same, for stderr. |
| `NAMAZU_CONDUCTOR_STDIO_PORT` | `10080` | WebSocket listen port |
| `NAMAZU_CONDUCTOR_STDIO_LOG_LEVEL` | `INFO` | Bridge's own log level |

## Wire Protocol

Three WebSocket endpoints, raw bytes only, no framing:

| Path | Direction | Semantics |
|---|---|---|
| `{base}/0` | client → server | stdin — frames forwarded to the child process's stdin |
| `{base}/1` | server → client | stdout — raw chunks as produced |
| `{base}/2` | server → client | stderr — raw chunks as produced |

The server closes `/1` and `/2` with WebSocket close code `4000 + <exit code>` (e.g. `4000` = clean
exit, `4007` = exit code 7; clamped to `4000`-`4255`, the valid range for a POSIX exit status). The
close reason string carries the signal name if the process was killed by a signal (e.g. `"SIGTERM"`),
otherwise empty.

Connecting to `/1`/`/2` immediately replays up to `NAMAZU_CONDUCTOR_STDIO_STDOUT_BUFFER_SIZE` /
`NAMAZU_CONDUCTOR_STDIO_STDERR_BUFFER_SIZE` bytes of the most recent output as a single message,
before switching to live streaming — a client that connects a moment after the process already
started producing output still sees recent history, not just whatever's written from that point on.
This is a bounded ring buffer, not a real log API: output older than the buffer's capacity is gone.

## Authentication

Every WebSocket connection (`/0`, `/1`, `/2`) must present `Authorization: Bearer <token>` during
the handshake, matching `NAMAZU_CONDUCTOR_STDIO_TOKEN`. Connections without it, or with the wrong
token, are closed immediately with code `1008`. There is no way to run the bridge without a token —
its stdio endpoints would otherwise be reachable, unauthenticated, by anyone who can reach the port.

`namazu-conductor` generates a fresh token per execution and passes it back automatically when
opening a stdio session — this isn't something you configure by hand; see `EdgeGapOrchestrationService`
(and the equivalent ECS provider) for how the token is threaded from deploy-time injection through
to `streamStdio`.

## Integration

The binary is published as a standalone executable (PyInstaller + `staticx`, no Python runtime
required, and portable across glibc- and musl-based images). Copy it into your own image via a
multi-stage build:

```dockerfile
COPY --from=ghcr.io/namazustudios/namazu-stdio-bridge:latest \
     /usr/local/bin/namazu-stdio-bridge /usr/local/bin/namazu-stdio-bridge
ENTRYPOINT ["/usr/local/bin/namazu-stdio-bridge"]
```

Your image's `CMD` (or the `command`/`args` a `JobRequest` supplies at runtime) becomes the argv
forwarded to whichever entrypoint candidate the bridge discovers — the bridge itself doesn't take
arguments meant for your process.

For EdgeGap/ECS, declare the bridge port (`10080` by default) in the app version's / task
definition's port mapping so `namazu-conductor` can reach it.

## Local development

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

NAMAZU_CONDUCTOR_STDIO_ENTRYPOINT=/path/to/your/entrypoint.sh \
  python -m namazu_stdio_bridge
```

Build the portable binary + image locally:

```bash
docker build -t namazu-stdio-bridge:local .
```
