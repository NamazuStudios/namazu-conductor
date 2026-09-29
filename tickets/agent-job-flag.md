# Ticket: Agent flag on `JobRequest`

**Status:** implemented
**Component:** `api` module (internal Kotlin API)
**Branch note:** no REST / UI / provider behavior changes by design.

## Summary

Add an `agent` designation to the internal job-request model. The flag marks a job
as an *agent* — a placeholder that no subsystem consumes yet. The intent is to have
the concept in place on the internal API so that other APIs and providers can be
built around it later without a disruptive model change.

## Motivation

Conductor needs to distinguish ordinary jobs from "agent" jobs. The exact semantics
are deliberately undefined for now (pending future work that will attach meaning to
the flag). This ticket only establishes the field and passes it through the public
internal contract.

## Scope

- [x] `JobRequest` gains `val agent: Boolean = false` (internal `api` module only).
- [ ] **Deferred** — no `DaemonRequest`/`JobExecution` mirror, no `ExecuteJobRequest`
      REST DTO, no admin UI control, no provider behaviour, no default environment
      injection. Each of these is intentional: the flag is a placeholder, and the
      REST API is frozen until the flag actually starts being used.

## Decisions

- **Internal API only.** The field lives on `dev.getelements.conductor.JobRequest`
  and is not plumbed into the REST request body. When consumer code arrives, the
  REST/UI surface should be added in the same change that defines the behaviour.
- **Per-request, not per-profile.** The flag describes an individual job invocation,
  so it belongs on `JobRequest` rather than `JobProfile`/`Daemon` (a profile can be
  launched as either kind).
- **Boolean, not an enum/kind.** A single `agent: Boolean` with a `false` default
  keeps the delta minimal for a placeholder; if agents later need subtypes, the
  field can be promoted to an enum or sealed type at that point.

## Acceptance criteria

1. `dev.getelements.conductor.JobRequest` exposes `agent: Boolean` defaulting to
   `false`.
2. KDoc states the field is a placeholder and not yet consumed.
3. All existing callers compile unchanged (default-valued parameter).
4. No REST schema, admin model, provider, or UI files are touched.