# Ticket: Free-form metadata for job profiles, daemons, and executions

**Status:** implemented (api, kubernetes, ecs, admin)
**Components:** `api`, `kubernetes`, `ecs`, `admin` (`edgegap` inherits the empty default)
**Branch:** `feature/job-metadata`

## Summary

Conductor knows a great deal about *how* to run a workload and nothing about *what it is for*. A
profile has an id, a description, and a terminal-job flag; the job set gets a name and a blurb.
Everything else an operator might want to attach to a job — a build number, a ticket reference, an
owner, a link to the dashboard that job feeds — had nowhere to live except a provider-specific
annotation or tag that Conductor silently ignored.

This adds a `Map<String, String> metadata` to `JobProfile`, `Daemon`, `JobRequest`, `DaemonRequest`,
`JobExecution`, and `DaemonExecution`.

## The load-bearing decision: no meaning

There is no well-known key set and no schema. Conductor assigns no meaning to any key. A consumer
that cares about `team` or `dashboard.url` decides what they mean; Conductor carries the strings.

The alternative — a fixed vocabulary of `owner`/`description`/`ticket` — was rejected because it
makes Conductor the place where new presentation fields must be added, which is exactly the coupling
this feature exists to remove. A presentation layer that insists on owning the vocabulary has to be
extended every time a consumer wants one more field, and the provider already has a perfectly good
mechanism for arbitrary key/value data that Conductor was throwing away.

The consequence, accepted deliberately: the raw map includes the `namazu.conductor` keys Conductor
also surfaces as typed fields. `namazu.conductor/workload-kind` appears in `metadata` as well as in
`workloadKind`. That is not an oversight — a consumer reading the map gets the complete picture
rather than a subset curated by something that doesn't know what the consumer wants.

## Provider coverage

| Provider | Declared from | Propagated to | Read back from |
|---|---|---|---|
| kubernetes | `PodTemplate.metadata.annotations` (top level) | `Pod` / `Job` / `Deployment` pod template | live workload |
| ecs | whole task-definition tag map | `runTask` tags / `createService` tags | task / service ARN |
| edgegap | — (no key/value channel) | — | — |

Only the **top-level** annotation block counts on Kubernetes. The inner `spec.template.metadata`
block belongs to the pod template proper and is not reported as declared metadata.

## Override semantics

`Metadata.merge(declared, overrides)` is the single implementation, shared by every provider so they
cannot drift. Overlay only: an override wins over the declared value, a key the caller doesn't
mention keeps its declared value, and a key the family/template never had can be added. There is no
way to *remove* a declared key. That is a real limitation, not an oversight — deletion would need a
tombstone syntax (`key=` meaning "unset this") which is a small protocol to invent and an awkward
thing to debug from a dashboard. Worth adding if a real caller needs it, not worth speculating.

## The reserved prefix

`namazu.conductor` is reserved, and a runtime override carrying it throws
`ReservedMetadataKeyException`. Those keys drive Conductor's own behaviour — the workload kind, the
exposed ports, the job's TTL and backoff limit. A caller overriding one would be asking the workload
to be one thing while Conductor manages it as another.

Two judgement calls here:

- **`JobException`, not `IllegalArgumentException`.** The original ask was an
  `IllegalArgumentException` subtype, but the platform's `execute()` handlers treat that as an
  internal error. A rejected metadata key is a caller error and deserves to be reported as one, so
  the exception extends `JobException` like every other contract violation in the `api` module.
  The admin REST layer maps it to 400 for the same reason.
- **Infrastructure may still declare reserved keys.** The restriction is on *overrides*, not on
  declarations. `Metadata.validate` is called on the override map only.

## Read-back is a superset, and that is intended

`JobExecution.metadata` / `DaemonExecution.metadata` are read off the live workload, not echoed from
the request. Two consequences worth stating plainly:

- On Kubernetes the reported set can exceed the declared one, because a template's inner
  `spec.template` block carries its own annotations onto the created pod. Reporting what landed
  beats forcing equality with what was requested — the difference is real information.
- On ECS, read-back needs `ecs:ListTagsForResource`. Without it the reported set silently degrades
  to the requested set and logs a warning. That is a deliberate choice: making metadata reporting a
  hard dependency on a new IAM grant would break status polling for every existing deployment that
  hasn't updated its policy yet. A configuration nicety should not be able to cause an outage.

`namazu.conductor/default-container-exec.*` annotations are synthesised by Conductor at dispatch time
rather than declared by the template, so they are excluded from both the declared and reported sets.
Reporting them as infrastructure metadata would be a lie.

## Admin dashboard

Profiles show their declared metadata as `key=value` pills (capped at four with a `+N more`
affordance — on Kubernetes this is every PodTemplate annotation, and rendering a dozen inline buries
the description and the Run button). Executions show theirs inside the expanded row. A value that
parses as an `http(s)` URL becomes a link; only `http(s)` is linkified, so a `javascript:` value can't
execute on click.

The run form has a "Metadata overrides" editor built on the existing `KVEditor`. Reserved-prefixed
keys are dropped client-side rather than sent to be refused — the field label states the rule, and
composing a key guaranteed to 400 is worse than not offering it. The server rejects them regardless,
since the dashboard isn't the only client.

## Known limitations

- **EdgeGap has no metadata channel.** Its deployment model is fully determined by the profile's
  app/version, so there is nowhere to put a key. It inherits the empty default rather than pretending
  otherwise.
- **No deletion semantics**, as above.
- **ECS read-back needs an IAM grant** that existing deployments haven't been asked to add yet.
- **The ECS integration tests remain disabled** pending
  [#35](https://github.com/NamazuStudios/namazu-conductor/issues/35), so the ECS side of this is
  compile-verified and reasoned about but not yet exercised against a real account.
