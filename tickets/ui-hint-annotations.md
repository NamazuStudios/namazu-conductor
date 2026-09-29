# Ticket: UI-hint annotations (`hidden`, `agent`, `link.{title}`) and the cosmetic-key override relaxation

**Status:** implemented
**Components:** `api`, `admin` (REST + UI)
**Branch note:** stacked on `feature/job-metadata` (#59) — the vocabulary is read from the
`metadata` maps that PR introduces.

## Summary

A built-in cosmetic vocabulary that Conductor (specifically the admin UI) understands, plus a
relaxation of the reserved-key override rule: behavioural `namazu.conductor` keys stay refused,
cosmetic ones become overridable.

## The vocabulary

| Key | Kubernetes form | ECS form | Meaning |
|---|---|---|---|
| `namazu.conductor/hidden` | `namazu.conductor/hidden` | `namazu.conductor:hidden` | `true` → row hidden unless "Show hidden" is checked |
| `namazu.conductor/agent` | `namazu.conductor/agent` | `namazu.conductor:agent` | `true` → 🤖 badge; a terminal job that is an agent |
| `namazu.conductor/link.{title}` | `namazu.conductor/link.Preview` | `namazu.conductor:link.Preview` | static http(s) URL as a clickable pill; repeat per title |

- **UI-interpreted, not typed.** No new API fields: the admin UI reads the keys from the raw
  `metadata` maps profiles/executions already report, so **no provider changes were needed** —
  declared metadata flows verbatim on kubernetes/ecs, and that is exactly what the UI reads.
  EdgeGap is inert (no metadata channel), as with all of `metadata`.
- **Key normalization.** Profiles report keys verbatim in their provider-native form, so the UI
  normalizes both separator forms (`/` and `:`) before matching (`conductorKeySuffix` in
  `admin/ui/superuser/ui.ts`, mirroring `Metadata.isBehavioral`).
- **Booleans are strict.** Only a value of `true` (case-insensitive) sets a flag; any other value
  means "not set". No tristate parsing surprises for a `FALSE` annotation.
- **Static links only.** `link.{title}` values are rendered exactly as declared — no per-run
  placeholder substitution against the execution's endpoints. Per-run links are still achievable
  by overriding the `link.*` key at launch, which the relaxation below now permits. If templating
  is wanted later, it needs rules for multi-container/multi-port workloads first.
- **Favicons without CORS.** The pill renders the origin's `/favicon.ico` as an `<img>` with an
  `onError` fallback to 🔗. An `<img>` load is not CORS-restricted — no `fetch`, no response
  inspection needed; a missing favicon simply errors and falls back. Only http(s) URLs are
  linkified (reusing the existing sanitizer); every value renders as a text child, and
  `target="_blank"` pairs with `rel="noopener noreferrer"`.
- **`agent` is cosmetic only.** The pre-existing `JobRequest.agent: Boolean` placeholder
  (`tickets/agent-job-flag.md`) stays internal and REST-frozen; the annotation does not set it.
  When the flag gains real semantics, the two should be reconciled in the same change.
- **Daemons.** No admin daemon surface exists yet (see #49), so the flags are metadata-only for
  daemons until one lands.

## The override relaxation

The blanket rule (any `namazu.conductor` override → `ReservedMetadataKeyException`) was overly
restrictive: per-run control of the cosmetic keys — launching the same template hidden or visible,
as agent or not, with different links — is a real need.

- **Behavioural keys stay refused.** `Metadata.isBehavioral` blocklists the union of every key any
  provider interprets as more than presentation: kubernetes (`workload-kind`, `expose-ports`,
  `service-type`, job/daemon tuning, session-secret, synthesized `default-container-exec`) and ECS
  (`workloadKind`, `jobSet`, `launchType`, `assignPublicIp`, `desiredCount`/`minCount`/`maxCount`).
  Suffixes are compared case-insensitively after stripping the prefix and either separator, so a
  mistyped casing can't smuggle a behavioural key past the guard.
- **Cosmetic and unknown keys pass.** `hidden`, `agent`, `link.*`, `description`, `display-name`,
  and any other reserved key not in the blocklist are overridable. Unknown keys are allowed at the
  caller's own risk: a future release may make one behavioural, and an override of it would then
  start being refused.
- **`ReservedMetadataKeyException` survives** with narrowed scope — renaming would churn every
  provider and the REST layer for no behavioural gain. Message and KDoc updated.
- **Admin REST** unchanged behaviourally: still pre-validates and answers 400, but only for
  behavioural keys now.
- **RunForm** mirrors the blocklist client-side (dropped keys were previously *all* reserved ones;
  now only behavioural ones) and its hint text states the new rule.

## Testing

- Kubernetes job/daemon metadata ITs: cosmetic (`namazu.conductor/hidden`) override accepted,
  lands on the workload, reads back; behavioural (`workload-kind`/`replicas`) still rejected with
  nothing created.
- ECS job/daemon metadata ITs (disabled per #35): same shape, `jobSet`/`desiredCount` as the
  behavioural rejects.
- Admin UI: `tsc --noEmit` clean; bundle rebuilt. UI logic (flag parsing, link extraction,
  favicon fallback) is plain functions where practical, but there is no component-test harness in
  this repo to exercise them — they are verified by the ITs upstream of the UI and by inspection.

## Amendment: container-scoped qualifiers (`hidden.{container}`, `agent.{container}`)

The `hidden` and `agent` flags gained a dot-qualified container form, following the
`default-container-exec.{container}` precedent — on ECS the same `:` separator form applies:

- `namazu.conductor/hidden.{container}` = `true` → just that container's attach row is hidden in
  the running-job's expanded view (the Show-hidden toggle reveals it, same opt-in as hidden jobs;
  hiding the last visible attach row collapses the empty Containers section).
- `namazu.conductor/agent.{container}` = `true` → 🤖 badge on that container's attach row.
  Badge only — it does not reorder the attach list.

Qualifiers are **independent of the job-level flags** (`hidden` does not need repeating per
container), and **display-only** — no provider filters containers from `JobProfile.containers` /
`JobExecution.containers`; the flags are read from the raw metadata maps the UI already has, so
again zero provider changes. `isBehavioral` is untouched: qualified suffixes aren't blocklisted,
so both forms stay cosmetic and overridable per run.

**Terminal deliberately stays pod-level.** A container-scoped terminal flag was considered and
rejected: unlike `hidden`/`agent`, a tty target is *launch behaviour* — the provider sets
`tty`+`stdin` on the primary container at dispatch (`applyTty`), so honouring a per-container
variant would change what Conductor creates, making it behavioural rather than cosmetic. The need
is already served display-side: the attach UI offers every container, and
`default-container-exec.{container}` customizes each row's default command. If per-container tty
targeting is ever wanted, it's a behavioural provider feature with its own ticket.

**New-tab convention.** Every link the dashboard renders from metadata — pill values and
`link.{title}` pills and links inside Markdown descriptions — opens in a new tab
(`target="_blank"` + `rel="noopener noreferrer"`, which keeps the opened page from reaching back
through `window.opener`), and pills mark it with a ↗ glyph. Markdown links get the same treatment
via a `marked` renderer override that also re-normalizes http(s) hrefs through `new URL`
(percent-encoding quotes, safe for a double-quoted attribute) and defers non-http(s) hrefs to
marked's default renderer, which neutralizes `javascript:` itself.

The kubernetes job metadata IT additionally pins the dot-qualified key
(`namazu.conductor/hidden.sidecar`) through the whole pipeline — declared, landed on the Job's pod
template, and read back — guarding the dot form against prefix-exclusion regressions (anything
that would swallow it the way `default-container-exec.*` is excluded).
