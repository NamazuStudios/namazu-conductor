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
