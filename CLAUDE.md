# Namazu Conductor — Claude Guide

## Project Overview

Namazu Conductor is a multi-module Maven project providing a unified container orchestration API for the [Namazu Elements SDK](https://namazustudios.com/docs). It abstracts provider-specific APIs (EdgeGap, AWS ECS, Kubernetes, Multiplay) behind a common `OrchestrationService` interface.

## Build & Run

**Requirements:** Java 21, Maven 3.9+, Docker (for local dev)

```bash
# Build all modules
mvn install

# Run local development environment (starts MongoDB via Docker Compose, then Elements runtime)
mvn install && cd debug && mvn exec:java
```

Each provider module produces both a JAR and a `.elm` archive (Namazu Elements module format).

## Module Structure

| Module | Purpose | Status |
|---|---|---|
| `api` | Core interfaces and data types — `OrchestrationService`, `JobRequest`, `JobExecution`, `JobProfile`, `JobPlacement`, `JobStatus` | Complete |
| `edgegap` | EdgeGap REST API v1 implementation | Complete |
| `ecs` | AWS ECS implementation (AWS SDK v2; Fargate and EC2 launch types) | Complete |
| `kubernetes` | Kubernetes implementation (Fabric8 client; `PodTemplate` → profile, `Pod`/`Job` workloads) | Complete |
| `multiplay` | Multiplay (Unity/Rocket Science) implementation | Skeleton (TODOs) |
| `debug` | Local runner — boots MongoDB replica set then starts Elements runtime | Complete |

## Key Abstractions

- **`OrchestrationService`** — the single interface all providers implement; binds via Guice PrivateModule
- **`JobProfile`** — provider-specific job template identified by a string ID
- **`JobPlacement`** — sealed hierarchy: `RegionPlacement`, `IpPlacement`, `LatitudeLongitudePlacement`; providers silently ignore unsupported placement types
- **`JobExecution`** — returned from `execute()`; tracks job by ID and `JobStatus`

## Provider Implementation Pattern

When implementing a new provider (e.g., Multiplay, Fargate):

1. Implement `OrchestrationService` in `<provider>/src/main/kotlin/.../service/`
2. Create a companion `JobProfile` data class (e.g., `EdgeGapJobProfile`) with an `id` derived from provider-native identifiers
3. Define configuration constants in an `Attributes` object using `@ElementDefaultAttribute` — convention: `API_KEY` (no default), `BASE_URL` (default provided)
4. Bind everything in a Guice `PrivateModule`, exposing only `OrchestrationService` to the parent injector
5. Annotate `package-info.java` with `@ElementDefinition` and `@GuiceElementModule` (must be Java, not Kotlin)

See `edgegap/` (REST-based) or `ecs/` (typed-SDK-based) for reference implementations.

## Kubernetes Provider Conventions

The `kubernetes` provider maps a `PodTemplate` resource to a `JobProfile`. Behaviour is driven by
labels and annotations on the `PodTemplate`:

- **Label** `namazu.conductor/job-set=<value>` — only templates matching the configured `JOBSET`
  attribute are surfaced as profiles (the per-instance filter; analogous to the ECS `jobSet` tag).
- **Annotation** `namazu.conductor/workload-kind` — `pod` (default; long-standing, bare `Pod`) or
  `job` (one-off, `batch/v1 Job`). Pod phases / Job status map onto `JobStatus`.
- **Annotation** `namazu.conductor/expose-ports` — e.g. `"7777/udp,8080/tcp"`. Present → a `Service`
  is created selecting the workload; absent → no `Service`, endpoints fall back to the pod IP.
- **Annotation** `namazu.conductor/service-type` — `NodePort` (default), `LoadBalancer`, or `ClusterIP`.

Created Services carry a `namazu.conductor/owned-by=<workload-name>` label so `stop()` can delete them
without persisting state. Only `RegionPlacement` is honoured (→ `topology.kubernetes.io/zone` node
selector). Client config comes from Fabric8 auto-detection (in-cluster or `~/.kube/config`) unless
`KUBECONFIG_PATH` / `MASTER_URL` attributes are set.

## Dependency Injection

- Google Guice 7 with `PrivateModule` per provider
- Configuration injected via `@Named` bindings using attribute constant strings
- Service and HTTP client singletons bound with `@Singleton`
- Jakarta RS `Client` provided in the module, configured with Jackson JSON support

## Artifact Format

Each provider is packaged as a `.elm` archive (ZIP) with this layout:
```
classpath/   — compiled classes and resources
api/         — API jars exported to other Elements
lib/         — bundled runtime dependencies
dev.getelements.element.manifest.properties
```

The `maven-antrun-plugin` in each provider POM assembles this structure during `install`.

## Languages

- **Kotlin** for all service implementations, models, and Guice modules
- **Java** only for `package-info.java` files (required by `@ElementDefinition` / `@GuiceElementModule` annotations)

## No Tests

There are currently no unit or integration tests in the project.