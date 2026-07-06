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
| `api` | Core interfaces and data types — `OrchestrationService`, `JobRequest`, `JobExecution`, `JobProfile`, `JobPlacement`, `JobScope`, `JobStatus` | Complete |
| `edgegap` | EdgeGap REST API v1 implementation | Complete |
| `ecs` | AWS ECS implementation (AWS SDK v2; Fargate and EC2 launch types) | Complete |
| `kubernetes` | Kubernetes implementation (Fabric8 client; `PodTemplate` → profile, `Pod`/`Job` workloads) | Complete |
| `multiplay` | Multiplay (Unity/Rocket Science) implementation | Skeleton (TODOs) |
| `debug` | Local runner — boots MongoDB replica set then starts Elements runtime | Complete |

## Key Abstractions

- **`OrchestrationService`** — the single interface all providers implement; binds via Guice PrivateModule
- **`JobProfile`** — provider-specific job template identified by a string ID
- **`JobPlacement`** — sealed hierarchy: `RegionPlacement`, `IpPlacement`, `LatitudeLongitudePlacement`; providers silently ignore unsupported placement types
- **`JobScope`** — sealed hierarchy: `NamespaceScope` (Kubernetes — overrides the default namespace a workload is created in), `ClusterScope` (ECS — overrides the default cluster a task is launched into); providers without an equivalent concept (e.g. EdgeGap, whose scoping is fully determined by the profile's app/version) silently ignore it
- **`JobExecution`** — returned from `execute()`; tracks job by ID and `JobStatus`

## Provider Implementation Pattern

When implementing a new provider (e.g., Multiplay, Fargate):

1. Implement `OrchestrationService` in `<provider>/src/main/kotlin/.../service/`
2. Create a companion `JobProfile` data class (e.g., `EdgeGapJobProfile`) with an `id` derived from provider-native identifiers
3. Define configuration constants in an `Attributes` object using `@ElementDefaultAttribute` — convention: `API_KEY` (no default), `BASE_URL` (default provided)
4. Bind everything in a Guice `PrivateModule`, exposing only `OrchestrationService` to the parent injector
5. Annotate `package-info.java` with `@ElementDefinition` and `@GuiceElementModule` (must be Java, not Kotlin)

### Element Declaration (`package-info.java`)

```java
@ElementDefinition(recursive = true)
@GuiceElementModule(MyProviderModule.class)
@ElementDependency("dev.getelements.elements.sdk.dao")
@ElementDependency("dev.getelements.elements.sdk.service")
package com.namazustudios.conductor.myprovider;
```

`@ElementDependency` declarations make SDK services available for `@Inject` within the module.

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
The following annotations apply to `job` workloads only (`namazu.conductor/workload-kind: job`).
All are optional integer strings; absent → field omitted (Kubernetes default applies); invalid
(non-numeric or negative) → warning logged, field omitted.

| Annotation | Job field | K8s default |
|---|---|---|
| `namazu.conductor/ttl-seconds-after-finished` | `spec.ttlSecondsAfterFinished` | none |
| `namazu.conductor/backoff-limit` | `spec.backoffLimit` | 6 |
| `namazu.conductor/active-deadline-seconds` | `spec.activeDeadlineSeconds` | none |
| `namazu.conductor/completions` | `spec.completions` | 1 |
| `namazu.conductor/parallelism` | `spec.parallelism` | 1 |

Created Services carry a `namazu.conductor/owned-by=<workload-name>` label so `stop()` can delete them
without persisting state. Only `RegionPlacement` is honoured (→ `topology.kubernetes.io/zone` node
selector). Client config comes from Fabric8 auto-detection (in-cluster or `~/.kube/config`) unless
`KUBECONFIG_PATH` / `MASTER_URL` attributes are set.

## Dependency Injection

- Google Guice 7 with `PrivateModule` per provider
- Configuration injected via `@Named` bindings using attribute constant strings
- Service and HTTP client singletons bound with `@Singleton`
- Jakarta RS `Client` provided in the module, configured with Jackson JSON support

Use `PrivateModule` to isolate bindings; expose only what other Elements need:
```java
public class MyProviderModule extends PrivateModule {
    @Override
    protected void configure() {
        bind(OrchestrationService.class).to(MyOrchestrationService.class);
        expose(OrchestrationService.class);
    }
}
```

## Maven Dependency Scopes

| Dependency | Scope | Reason |
|---|---|---|
| `sdk`, `sdk-local` | `provided` | Supplied by the Elements runtime |
| `api` module (your own) | `provided` in `element`/provider modules | Exported separately via the `api/` directory |
| `sdk-spi` + `sdk-spi-guice` | **bundled** (compile/runtime) | Must ship inside the `.elm` |
| `sdk-logback` | bundled | Use instead of plain logback to avoid classpath conflicts |

### Kotlin stdlib and the API classloader

When a module exposes Kotlin types (data classes, enums) in REST response bodies, the platform's Swagger/Jackson scanner runs in the API classloader which does **not** automatically include `kotlin-stdlib`. This causes `NoClassDefFoundError` for Kotlin internal types at startup.

**Fix:** add a `copy-dependencies` execution to the module's `pom.xml` to copy `kotlin-stdlib` into the `api/` directory of the `.elm` archive:

```xml
<execution>
    <id>elm-copy-kotlin-stdlib-api</id>
    <phase>prepare-package</phase>
    <goals><goal>copy-dependencies</goal></goals>
    <configuration>
        <outputDirectory>${elm.element.dir}/api</outputDirectory>
        <includeGroupIds>org.jetbrains.kotlin</includeGroupIds>
        <prependGroupId>true</prependGroupId>
    </configuration>
</execution>
```

### Jackson and Kotlin data classes

The REST layer's `ObjectMapper` has **no Kotlin module** (same classloader constraint). Jackson cannot discover a Kotlin data class primary constructor on its own, so:

- **Response-only DTOs** in the provider module serialize fine (Jackson uses getters).
- **Request body DTOs** that are Kotlin data classes need explicit `@JsonCreator`/`@JsonProperty` annotations, or a Jackson mixin if the class lives in the `api` module (which must stay Jackson-free).

## Key SDK Types

| Type | Purpose |
|------|---------|
| `ElementSupplier.getElementLocal(Class<?>)` | Get the Element from its own classpath |
| `Element.getServiceLocator()` | Access Guice-managed services |
| `ServiceLocator.getInstance(Class<T>)` | Get an injected instance (throws if missing) |
| `ServiceLocator.findInstance(Class<T>)` | Returns `Optional<Supplier<T>>` |
| `ElementScope` / `element.withScope()` | Thread-local scope with mutable attributes |
| `Element.publish(Event)` | Broadcast events to other Elements |

## Artifact Format

Each provider is packaged as a `.elm` archive (ZIP) with this layout:
```
classpath/   — compiled classes and resources
api/         — API jars exported to other Elements
lib/         — bundled runtime dependencies
dev.getelements.element.manifest.properties
dev.getelements.element.attributes.properties   (optional — deploy-time attribute overrides)
```

The `maven-antrun-plugin` in each provider POM assembles this structure during `install`.

### Embedding Attributes in the ELM

To ship deploy-time attribute defaults inside the archive, place a properties file at:
```
<provider>/src/main/elm/dev.getelements.element.attributes.properties
```

Then add an antrun copy step in `<provider>/pom.xml` to stage it to the element root:
```xml
<copy todir="${elm.element.dir}" failonerror="false">
    <fileset dir="${basedir}/src/main/elm" erroronmissingdir="false" includes="**/*"/>
</copy>
```

`@ElementDefaultAttribute` on static fields provides compile-time defaults; this file overrides them at deploy time without recompiling.

## Languages

- **Kotlin** for all service implementations, models, and Guice modules
- **Java** only for `package-info.java` files (required by `@ElementDefinition` / `@GuiceElementModule` annotations)

## Elements REST API Reference

The full Elements platform REST API is available as an OpenAPI spec at:
```
http://localhost:8080/api/rest/openapi.json
```

When building a provider that needs to call platform services, browse `dev.getelements.elements.rest` and subpackages in `~/.m2/repository/dev/getelements/elements/`. Key subpackages:

| Subpackage | Domain |
|---|---|
| `dev.getelements.elements.rest.user` | User CRUD, password management |
| `dev.getelements.elements.rest.security` | Sessions, auth |
| `dev.getelements.elements.rest.element` | Element deployment and status |
| `dev.getelements.elements.rest.application` | Application and platform config |

## Dashboard UI Plugins

Provider modules can inject custom debug pages into the Elements dashboard by shipping a React component bundle. The dashboard discovers these at runtime via a `plugin.json` manifest — no dashboard changes required.

### Layout inside the `.elm`

Place the manifest and bundle under the provider's UI content directory:
```
<provider>/src/main/ui/
  superuser/
    plugin.json        # declares sidebar entry and bundle location
    plugin.bundle.js   # self-contained IIFE bundle
```

These are packaged into the `.elm` at build time and served under `/app/ui/{element-prefix}/{segment}/`.

### plugin.json

```json
{
  "schema": "1",
  "entries": [
    {
      "label": "Conductor — EdgeGap",
      "icon": "Layers",
      "bundlePath": "plugin.bundle.js",
      "route": "conductor-edgegap"
    }
  ]
}
```

| Field | Description |
|---|---|
| `label` | Text shown in the dashboard sidebar |
| `icon` | A [Lucide](https://lucide.dev/icons/) icon name (e.g. `Layers`, `Package`, `Zap`) |
| `bundlePath` | Path to the bundle, relative to the manifest |
| `route` | Unique key used in the dashboard URL (`/plugin/{route}`) |

### Bundle format

The bundle must be an IIFE that registers a React component with the dashboard's plugin registry. Use `window.React` — do not bundle React separately.

```js
(function () {
  var React = window.React;
  function ConductorDebug() {
    return React.createElement('div', { className: 'p-6' }, 'Conductor Debug');
  }
  window.__elementsPlugins && window.__elementsPlugins.register('conductor-edgegap', ConductorDebug);
})();
```

Tailwind utility classes work out of the box — the dashboard stylesheet is already loaded.

### Building the UI

The `ui/` Maven module (if present) is a Vite/TypeScript project. One-time setup:
```bash
cd ui && npm install
```

Dev server (fast iteration):
```bash
npm run dev:superuser
# Open http://localhost:5173
```

Build for integration (writes bundle into `<provider>/src/main/ui/superuser/`):
```bash
npm run build
```

Then restart `mvn -pl debug exec:java` to pick up the new bundle.

**CI / Maven:** activate the `build-ui` profile to run npm via Maven:
```bash
mvn install -Pbuild-ui
```

### User segmentation

`superuser/` serves components shown to administrators only. `user/` serves components in user-facing dashboards.

## No Tests

There are currently no unit or integration tests in the project.