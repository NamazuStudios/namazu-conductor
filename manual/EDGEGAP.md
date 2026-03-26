# EdgeGap Provider

## What Is EdgeGap?

[EdgeGap](https://edgegap.com) is a fleet computing platform built specifically for game servers. Unlike general-purpose cloud providers, EdgeGap's infrastructure is designed from the ground up for the demands of real-time multiplayer games: low latency, fast cold starts, global distribution, and per-minute billing that makes on-demand session servers economically viable at any scale.

EdgeGap operates a worldwide network of points of presence. When you deploy a game server through EdgeGap, it automatically routes the deployment to the node geographically closest to your players - reducing the round-trip time between client and server and improving the player experience without any manual region management on your part.

**EdgeGap resources:**

- [EdgeGap Documentation](https://docs.edgegap.com)
- [EdgeGap: Dockerizing Your Game Server](https://docs.edgegap.com/docs/deployment/docker-game-server)
- [EdgeGap Dashboard](https://app.edgegap.com)

---

## Why EdgeGap for Game Servers?

| Concern | EdgeGap |
|---|---|
| Latency | Global edge network routes players to the nearest server automatically |
| Cost | Per-minute billing; you pay only while a session is active |
| Cold start | Containers start in seconds, suitable for on-demand match servers |
| Game-focused | Built for UDP/TCP game traffic, not general web workloads |
| Placement | Supports IP-based and coordinate-based placement for proximity routing |

EdgeGap is best suited to **session-scoped workloads** - game servers that have a clear start and end. A battle royale match, a co-op raid, an instanced dungeon - each is one deployment that lives for the duration of the session and is terminated when it finishes. The combination of fast startup and per-minute billing makes this model cost-effective even at high match volumes.

---

## How EdgeGap Integrates with Namazu Conductor

Namazu Conductor's EdgeGap provider maps EdgeGap's concept of **applications** and **versions** onto Conductor's `JobProfile` abstraction. You define your game server as an application in the EdgeGap dashboard with one or more versions (e.g., different map configurations or build variants). Conductor discovers those versions and exposes them as `JobProfile` instances your backend code can select from.

The profile ID format is `appName:versionName`, matching the names as they appear in your EdgeGap account.

### Configuration

The EdgeGap element is configured via two Namazu Elements attributes:

| Attribute | Key | Default |
|---|---|---|
| API Key | `dev.getelements.conductor.edgegap.api.key` | *(required, no default)* |
| Base URL | `dev.getelements.conductor.edgegap.base.url` | `https://api.edgegap.com` |
| Poll Interval | `dev.getelements.conductor.edgegap.poll.interval.ms` | `5000` |

Set `dev.getelements.conductor.edgegap.api.key` to the API token from your EdgeGap dashboard. The base URL and poll interval can be left at their defaults for production use.

---

## Code Examples

All examples below use the `OrchestrationService` interface injected into your Namazu Elements service via Guice. The EdgeGap implementation is wired automatically by `EdgeGapOrchestrationModule` - your backend code depends only on `OrchestrationService` and never on EdgeGap-specific types directly.

### Listing Available Profiles

Discover the game server versions registered in your EdgeGap account:

```kotlin
@Inject
lateinit var orchestration: OrchestrationService

fun listProfiles() {
    val profiles = orchestration.getAvailableProfiles()
    profiles.forEach { profile ->
        println("Available profile: ${profile.id}")
    }
}
```

Profile IDs take the form `appName:versionName`, for example `my-game:battle-royale` or `my-game:co-op-raid`.

### Starting a Session Server

Find a profile by ID and launch a deployment. Pass environment variables to configure the server at runtime:

```kotlin
fun startMatchServer(matchId: String, map: String, maxPlayers: Int, backendUrl: String): JobExecution {
    val profile = orchestration.findAvailableProfile("my-game:battle-royale")
        ?: error("Profile not found - check EdgeGap app/version names")

    return orchestration.execute(
        JobRequest(
            profile = profile,
            environment = mapOf(
                "MATCH_ID"     to matchId,
                "MAP_NAME"     to map,
                "MAX_PLAYERS"  to maxPlayers.toString(),
                "BACKEND_URL"  to backendUrl,
                "BACKEND_SECRET" to "<shared secret>"
            )
        )
    )
}
```

### Waiting for the Server to Be Ready

`execute()` returns immediately with a `PENDING` execution. Use `getFutureForStatus()` to block until the server is `RUNNING` and its endpoints are available:

```kotlin
fun waitForServer(execution: JobExecution): JobExecution {
    val running = orchestration
        .getFutureForStatus(execution, JobStatus.RUNNING)
        .get(5, TimeUnit.MINUTES)   // fail if not up within 5 minutes

    val endpoint = running.endpoints.first()
    println("Server ready at ${endpoint.host}:${endpoint.port} (${endpoint.protocol})")
    return running
}
```

Once `RUNNING`, `JobExecution.endpoints` contains the host, port, and protocol for each exposed container port. Hand this address to your matchmaker or directly to players so they can connect.

### Placement - Routing Players to the Nearest Server

EdgeGap supports two placement strategies that influence which edge node receives the deployment.

**By player IP address** - EdgeGap routes to the node closest to the given IP:

```kotlin
val execution = orchestration.execute(
    JobRequest(
        profile = profile,
        placement = listOf(IpPlacement(ip = playerIpAddress)),
        environment = mapOf("MATCH_ID" to matchId)
    )
)
```

**By coordinates** - useful when you already know the geographic centroid of your player group:

```kotlin
val execution = orchestration.execute(
    JobRequest(
        profile = profile,
        placement = listOf(LatitudeLongitudePlacement(latitude = 48.8566, longitude = 2.3522)),
        environment = mapOf("MATCH_ID" to matchId)
    )
)
```

Placement hints are best-effort. EdgeGap will use them to select the optimal deployment location but may fall back to another node if the nearest one is unavailable.

### Stopping a Server

Stop a running server when the session ends to release infrastructure and stop billing:

```kotlin
fun stopServer(execution: JobExecution) {
    orchestration.stop(execution)
}
```

Call `stop()` as soon as the match concludes - either triggered by the game server calling back to your backend, or by a timeout if the server goes silent unexpectedly. Leaving servers running after sessions end wastes money and may exhaust your deployment quota.

---

## A Complete Match Lifecycle

Putting it all together, a typical match flow looks like this:

```kotlin
// 1. Matchmaking assembles players and triggers a server launch
val execution = startMatchServer(
    matchId   = "match-abc-123",
    map       = "desert_canyon",
    maxPlayers = 64,
    backendUrl = "https://api.mygame.com"
)

// 2. Wait for the server to be ready
val running = waitForServer(execution)

// 3. Dispatch players to the server
val endpoint = running.endpoints.first()
dispatchPlayersToServer(players, endpoint.host, endpoint.port)

// 4. The game server calls the backend when the match ends (via REST)
//    The backend then stops the deployment
stopServer(running)
```

The game server itself is responsible for calling your backend when the match concludes. The backend verifies the result, records any outcomes, and calls `stop()` to terminate the EdgeGap deployment. This keeps the authoritative end-of-match logic on infrastructure you control rather than in the client.