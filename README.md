# Namazu Conductor

Namazu Conductor is a container orchestration framework built on the [Namazu Elements SDK](https://namazustudios.com/docs). It provides a unified API for dispatching containerized jobs across multiple cloud and fleet providers, abstracting provider-specific details behind a common `OrchestrationService` interface.

## Supported Providers

| Module | Provider | Description |
|---|---|---|
| `edgegap` | [EdgeGap](https://edgegap.com) | Geo-distributed game server orchestration; supports GPS-based placement |
| `multiplay` | [Multiplay](https://unity.com/products/multiplay) (Rocket Science / formerly Unity) | Managed game server hosting |
| `fargate` | [AWS Fargate](https://aws.amazon.com/fargate/) | Serverless container execution on AWS |

## Architecture

Conductor is a multi-module Maven project. Each provider is packaged as a self-contained Namazu Elements module (`.elm` archive) that binds its `OrchestrationService` implementation via Google Guice.

```
conductor/
├── api/          # Core interfaces and data types — the contract all providers implement
├── edgegap/      # EdgeGap OrchestrationService implementation
├── multiplay/    # Multiplay OrchestrationService implementation
├── fargate/      # AWS Fargate OrchestrationService implementation
└── debug/        # Local development runner
```

### Core API (`dev.getelements.conductor`)

#### `OrchestrationService`

The central service interface exported by every provider module:

```kotlin
interface OrchestrationService {
    fun getAvailableProfiles(): List<JobProfile>
    fun findAvailableProfile(id: String): JobProfile?
    fun execute(request: JobRequest): JobExecution
}
```

- **`getAvailableProfiles()`** — returns the pre-configured job templates available on the provider. Profile contents (container image, resource limits, etc.) are managed within the provider's own ecosystem.
- **`findAvailableProfile(id)`** — convenience lookup; returns `null` if no profile with the given ID exists.
- **`execute(request)`** — submits a `JobRequest` and returns a `JobExecution` tracking the running workload.

#### `JobRequest`

Describes the workload to run:

```kotlin
data class JobRequest(
    val profile: JobProfile,
    val args: List<String> = emptyList(),
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val placement: List<JobPlacement> = emptyList()
)
```

#### `JobPlacement`

Optional hints that influence where a job is scheduled. Unsupported placement types are silently ignored by the provider.

| Implementation | `PlacementType` | Fields | Supported by |
|---|---|---|---|
| `RegionPlacement` | `REGION` | `id: String` | EdgeGap, Multiplay |
| `IpPlacement` | `IP_ADDRESS` | `ip: String` | Any |
| `LatitudeLongitudePlacement` | `LAT_LON` | `latitude: Double`, `longitude: Double` | EdgeGap |

#### `JobExecution`

Returned by `execute()`, represents the running workload:

```kotlin
data class JobExecution(
    val id: String,
    var status: JobStatus   // PENDING, RUNNING, COMPLETED, FAILED
)
```

## Building

Requires Java 21 and Maven 3.9+.

```bash
mvn install
```

Each provider module produces a `.elm` archive suitable for deployment into a Namazu Elements runtime.

## Local Development

The `debug` module provides a local runner that boots a MongoDB replica set via Docker Compose and starts a local Elements environment:

```bash
# From the project root
mvn install
cd debug && mvn exec:java
```

Docker must be running. The `services-dev/docker-compose.yml` starts MongoDB 6.0 with a replica set configured for local use.

## License

BSD 3-Clause — see [LICENSE](LICENSE).