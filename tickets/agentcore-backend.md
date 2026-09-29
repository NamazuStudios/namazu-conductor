# Ticket: AWS Bedrock AgentCore as an orchestration backend

**Status:** deferred
**Components:** new `agentcore` module (`api` conventions, AWS SDK v2)
**Branch note:** research only — no code yet. This ticket preserves the design so the
investigation doesn't have to be redone when the work is picked up.

## Summary

Add an `agentcore` provider module implementing `OrchestrationService` on top of
Amazon Bedrock AgentCore Runtime (V2, GA September 2026): serverless microVMs in
AWS that run container-based agent images (ARM64/ECR, up to 2 GB), hardware-isolated
per session, scale-to-zero. This gives Conductor a first-class way to execute
container-based agent workloads natively in AWS.

Scope confirmed: **v1 includes interactive terminals**, and `command`/`args` map to
AgentCore's one-shot shell API. Invoke-only — runtimes must pre-exist in the AWS
account (same posture as ECS task definitions and EdgeGap apps).

## Model mapping

### `AgentCoreJobProfile`

An existing AgentCore Runtime, identified by `agentRuntimeArn` (or agent ID +
account ID) plus an optional endpoint `qualifier`. Discovered via the control
plane (`bedrock-agentcore-control` `ListAgentRuntimes`), optionally filtered by a
`namazu.conductor:jobSet` resource tag — mirrors the ECS tag convention
(`EcsAttributes.JOBSET` / `TAG_JOBSET`). Runtimes carry `name`/`description`,
which feed issue #49 Phase 1 (`JobProfile.name`/`description`) for free.

### `execute()`

`InvokeAgentRuntime` (data plane) with a fresh `runtimeSessionId`. One-shot job =
one invocation/session. Terminal `JobStatus` when the response stream completes;
failed invocation → `FAILED`. The invocation payload is **opaque** (up to 100 MB)
and is supplied via a new optional `JobRequest` field — payload-only for the agent
invocation itself (per-invocation environment is not possible; env vars are
runtime-level config).

### `command`/`args` → `InvokeAgentRuntimeCommand`

One-shot, stateless shell execution inside the same session container, over
HTTP/2 with streamed `stdout`/`stderr` and a structured exit code. Timeout is
configurable per call (1–3,600 s, default 300). Each command starts a fresh shell
process (no persistent state) — matches Conductor's cross-provider
command/args semantics. `environment` applies to command executions
(session-level), not to agent invocations.

### Terminals (in v1)

`tty=true` → create the session and expose it via `streamStdio` by bridging the
admin xterm.js websocket to AgentCore's **interactive shell** —
`InvokeAgentRuntimeCommandShell`, a persistent, **PTY-backed** terminal over
WebSocket (binary frames: raw terminal I/O, colors, Ctrl+C, resize, tab
completion). Launched June 5, 2026 — this is native pty, not an emulation.

- `JobExecution.details` carries `runtimeSessionId` + `shellId` for reconnection
  (1 h max per shell connection; reconnect with the same IDs to resume, up to
  256 KB of buffered output replay — matches the admin terminal widget's
  existing reconnect behavior).
- **≤ 10 concurrent shells per runtime**; new connections rejected at capacity.
- Caveat: runtimes created after **June 5, 2026** support shells automatically;
  older runtimes must be redeployed.

### Ignored / unsupported

- `placement` (`RegionPlacement`, `IpPlacement`, `LatitudeLongitudePlacement`) —
  silently ignored; AgentCore has no placement concept.
- `scope` — silently ignored; no namespace/cluster equivalent.
- `stop()` — stop the runtime session.
- No `DaemonOrchestrationService` — sessions are invocation-scoped, not
  replica-managed.
- `endpoints` empty — AgentCore does not expose network endpoints to the caller.
- Admin terminal-attach authorization must participate in the
  `TerminalAttachPolicy` SPI (a55620e).

## Platform facts

| Item | Value |
|---|---|
| Container requirement | `linux/arm64` (Graviton) — mandatory |
| Image cap | 2 GB via ECR |
| Invoke protocol | HTTP/2 / SSE streaming; payloads up to 100 MB |
| Shell (one-shot) | `InvokeAgentRuntimeCommand` — HTTP/2, structured events, exit code |
| Shell (interactive) | `InvokeAgentRuntimeCommandShell` — WebSocket, PTY-backed, binary frames |
| IAM permissions | `bedrock-agentcore:InvokeAgentRuntime`, `InvokeAgentRuntimeCommand`, `InvokeAgentRuntimeCommandShell` (plus `...ForUser` variants if per-user headers are ever needed) |
| Quotas | 1,000 active sessions (us-east-1/us-west-2), 25 TPS invoke, 100 TPM new sessions (container) |
| Auth | IAM SigV4 (default); JWT/OAuth authorizers exist but out of scope |
| Provisioning | CFN `AWS::BedrockAgentCore::Runtime` / CDK alpha L2 — out of scope for v1 |

## Module conventions (per CLAUDE.md)

- Kotlin service: `AgentCoreOrchestrationService`, `AgentCoreJobProfile` (companion
  `JobProfile` data class, `id` derived from the runtime ARN).
- `AgentCoreAttributes` object with `@ElementDefaultAttribute` — `REGION` (no
  default), `JOBSET` (optional tag filter), any qualifier defaults.
- Guice `PrivateModule`; expose only `OrchestrationService`.
- Java `package-info.java` with `@ElementDefinition(recursive = true)` /
  `@GuiceElementModule` / `@ElementDependency` declarations.
- AWS SDK v2 clients: `bedrockagentcore` (data plane) +
  `bedrockagentcorecontrol` (discovery).
- `.elm` packaging + `kotlin-stdlib` copy-dependencies execution (the Kotlin
  stdlib classloader constraint documented in CLAUDE.md).
- `debug` deployment and `admin` aggregation work automatically via the
  standard `OrchestrationService` lookup.

## Risks

- **Java SDK has no first-class helper for the shell WebSocket.** The SDK exposes
  HTTP/2 operations, but `InvokeAgentRuntimeCommandShell` is a WebSocket upgrade
  that must be opened client-side with SigV4 (signed headers or pre-signed URL).
  Implementation needs a manually signed WebSocket upgrade (OkHttp or JDK
  WebSocket + SigV4 signing). This is the main unknown — prototype it first.
- **No one-shot `JobRequest.command` failure semantics** beyond the exit code:
  map non-zero exit → `FAILED` (or record in `details`) — decide at
  implementation time.
- Runtimes older than 2026-06-05 silently lack shell support — surface a clear
  error when a `tty` job targets one.

## Out of scope

- Runtime/ECR provisioning (invoke-only v1; control-plane creation is a possible
  future phase).
- `DaemonOrchestrationService`.
- Integration-test harness — deferred (the removed `multiplay` module shipped
  without IT coverage; don't repeat that).
  A small CFN stack creating a runtime, or reuse of the `agentcore` CLI's deploy,
  can come later.
- WebRTC / bidirectional voice streaming (March 2026 feature — unrelated to
  terminals).

## References

- InvokeAgentRuntime — https://docs.aws.amazon.com/bedrock-agentcore/latest/APIReference/API_InvokeAgentRuntime.html
- InvokeAgentRuntimeCommand — https://docs.aws.amazon.com/bedrock-agentcore/latest/APIReference/API_InvokeAgentRuntimeCommand.html
- Shell execution overview — https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/runtime-shell-execution.html
- Interactive shells (terminals) — https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/runtime-get-started-command-shell.html
- Announcement (shells) — https://aws.amazon.com/about-aws/whats-new/2026/06/amazon-bedrock-agentcore-runtime
- Runtime overview — https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/agents-tools-runtime.html
