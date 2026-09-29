# Ticket: Friendly names for job profiles, daemons, and executions

**Status:** deferred
**Components:** `api`, `kubernetes`, `ecs`, `edgegap`, `multiplay`, `admin`
**Branch note:** research only — no code yet. This ticket exists to preserve the
findings so the investigation doesn't have to be redone when the work is picked up.

**Amendment:** the `multiplay` provider module was removed from the repository
after this ticket was written, so its three findings below are historical only —
the `MultiplayJobProfile.name` promotion is no longer a "near-free win", and
`multiplay` no longer appears in the provider matrices. The `kubernetes`, `ecs`,
and `edgegap` findings stand as written.

## Summary

Conductor has no first-class friendly-name concept below the job-set level. This
ticket proposes adding one in two phases:

- **Phase 1 (deliverable):** a `name` on `JobProfile` and `Daemon`, plus
  `jobSetName`/`jobSetDescription` on `DaemonOrchestrationService`, surfaced in
  the admin dashboard.
- **Phase 2 (deferred, provider-gated):** a per-request `name` on `JobRequest`
  carried onto `JobExecution` — only viable in providers that can persist
  per-workload metadata.

## Motivation

Every admin UI surface labels jobs and templates with the raw provider-native id
(e.g. `default:job:my-template-3f2a1b4c`, a task ARN, an EdgeGap request UUID).
Job-set naming (issue #16, `feature/16-jobset-friendly-name-description`, merged
to `main`) solved the provider-level label, but everything below it — individual
templates, daemons, running jobs — still shows ids only.

## Current state

- No `name`/`displayName`/`label`/`title` on `JobProfile`, `JobRequest`,
  `Daemon`, `DaemonRequest`, `JobExecution`, or `DaemonExecution`.
- `OrchestrationService.jobSetName`/`jobSetDescription`
  (`api/.../service/OrchestrationService.kt:28-38`) — implemented by `kubernetes`
  and `ecs` only; `edgegap`/`multiplay` inherit the `null` default.
- `JobProfile.description` (`api/.../service/JobProfile.kt:39`) exists but only
  `kubernetes` populates it, from the `namazu.conductor/description` PodTemplate
  annotation (`ANN_DESCRIPTION`, `KubernetesOrchestrationService.kt:1264`, read
  at `:157`).
- `MultiplayJobProfile.name` (`multiplay/.../MultiplayJobProfile.kt:17`) is a
  real friendly name from the build configuration, but it is **not** part of the
  `JobProfile` contract — generic consumers can't see it, and the admin UI
  buries it in the collapsed Details grid.
- `Daemon` has only `id` (`api/.../service/Daemon.kt:16`), and
  `DaemonOrchestrationService` has no `jobSetName`/`jobSetDescription` mirror.
  The admin module has **no daemon surface at all** (no REST resource, no UI
  page), so daemon naming would be API-only for now.
- Admin UI labels rows with raw ids: `AvailableJobsPage.ts:61` renders
  `profile.id` as the row label; `RunningJobs.ts:66` shows only `ex.id`.

## The blocker: per-request names cannot round-trip in every provider

`JobExecution` is re-derived from the live workload on every poll across 18
construction sites (kubernetes 8: `:313, :641, :652, :655, :682, :695, :698,
:814`; ecs 3: `:467, :534, :567`; edgegap 3: `:136, :191, :214`; multiplay 3:
`:110, :200, :230`; admin 1: `ConductorAdminJobsResource.kt:196`). A per-request
name would be **lost on the next poll** unless persisted on the workload
resource itself — and the four providers differ sharply:

| Provider | Per-request name round-trips? | Cost |
|---|---|---|
| kubernetes | Yes | Moderate — write a label/annotation at create time (`KubernetesOrchestrationService.kt:242-300`), read it back in the `LABEL_OWNED_BY` re-derivation loops (`:641-:698`) and `resultFor` (`:814`). |
| ecs | Only with new API + IAM work | High — `runTask` passes no tags (`EcsOrchestrationService.kt:519-529`) and task reads return none (only `taskDef.tags()` at `:731` is used, for profiles). Needs `tags()` on `runTask` plus an `include=TAGS` or `ListTagsForResource` read path, and IAM changes to the IT stack policy. The ECS IT is currently disabled (#35). |
| edgegap | **No** | `/v1/deploy`, `/v1/deployments`, and `/v1/status/{id}` expose only appName/versionName/requestId — no per-deployment metadata channel exists. |
| multiplay | **No** | The allocation API has no per-allocation metadata. |

**Decision made (this session):** per-profile + per-request naming, all four
providers, `Daemon.name` included. The provider matrix above is why Phase 2
must be deferred and provider-gated — two of the four providers cannot deliver
it at all with their current APIs.

## Phase 1 (deliverable)

Per-profile names, declared on the template, visible everywhere:

- `JobProfile` gains `val name: String?` (default `null`); `Daemon` gains
  `name`/`description` the same way; `DaemonOrchestrationService` gains
  `jobSetName`/`jobSetDescription` mirroring `OrchestrationService`.
- Name sources:
  - **kubernetes** — new `namazu.conductor/display-name` annotation beside
    `ANN_DESCRIPTION`, read in `toProfile` (`:157`) and `toDaemon` (`:185-195`).
  - **ecs** — new `namazu.conductor:displayName` task-definition tag; tags are
    already fetched (`describeTaskDefinition` requests `TAGS` at `:118`/`:175`),
    so no extra API round trip.
  - **multiplay** — *(module since removed; no longer applicable.)* Previously
    promote the existing `MultiplayJobProfile.name` onto the contract.
  - **edgegap** — no name source exists on the wire; falls back to `id`
    (`"$appName:$versionName"`).
- Admin: render `name` as the row label with `id` fallback
  (`AvailableJobsPage.ts:61`); add `name` to the TS `JobProfile` interface
  (`types.ts:22-28`) and to the `DetailGrid` exclusion list
  (`AvailableJobsPage.ts:57-58`). `ExecuteJobRequest` is **untouched** in
  Phase 1.

## Phase 2 (deferred, provider-gated)

Per-request `name` on `JobRequest` → `JobExecution.name`:

- **kubernetes** — deliverable as described in the blocker matrix.
- **ecs** — blocked on the `runTask` tags + read-path + IAM work; also gated on
  the ECS IT re-enable (#35).
- **edgegap / multiplay** — not deliverable with current provider APIs; needs a
  provider-side metadata capability that doesn't exist today.
- Admin: `ExecuteJobRequest` gains `name`; running-jobs rows
  (`RunningJobs.ts:66`) show the name with `id` fallback.

## Admin follow-ups (both phases)

- `admin/ui/superuser/types.ts:22-28` — add `name` to the `JobProfile` interface.
- `admin/ui/superuser/AvailableJobsPage.ts:61` — row label becomes
  `profile.name ?? profile.id`.
- `admin/ui/superuser/AvailableJobsPage.ts:57-58` — add `name` to the
  `DetailGrid` exclusion list so it isn't duplicated as a generic row.
- `admin/ui/superuser/RunningJobs.ts:66` — execution row label (Phase 2 only).
- Daemon naming is API-only until admin gains a daemon surface (none exists).

## Out of scope

- The `agent: Boolean` placeholder on `JobRequest` — considered this session and
  deliberately **not** reintroduced (see `tickets/agent-job-flag.md`).
- Any change to `ExecuteJobRequest` or the REST schema in Phase 1.
- Rediscovering/fixing the job-set deployment aggregation caveats
  (`elements#99`/`elements#103`) — separate concerns.
