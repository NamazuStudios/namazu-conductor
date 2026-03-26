# Docker - A Crash Course for Game Developers

> **Note:** This guide is intended as a rough overview of Docker and how it relates to game server infrastructure. It is not a comprehensive Docker tutorial. Once you have a feel for the concepts here, you are strongly encouraged to work through the official tutorials linked below for a more thorough understanding before building production infrastructure.

Docker is a tool for packaging and running software in isolated, lightweight containers. A container bundles your application code together with everything it needs to run - the operating system libraries, runtime, configuration files, and dependencies - into a single portable unit called an **image**. That image can be started in seconds and run identically on any machine that has Docker installed.

For game server infrastructure, this matters enormously. A container image of your game server can be deployed to thousands of instances in a matter of seconds, each starting from the same known state, behaving the same way, and terminating cleanly when its job is done.

**External resources to get started:**

- [Docker Official Get Started Guide](https://docs.docker.com/get-started/)
- [Docker Official Tutorials](https://docs.docker.com/guides/)
- [EdgeGap: Dockerizing Your Game Server](https://docs.edgegap.com/docs/deployment/docker-game-server)
- [Docker Hub](https://hub.docker.com/) - the public registry for pre-built images

---

## Running Your Actual Game Server in Docker

One of the most important things to understand is that you do not have to rewrite your game server in the same language as your backend. Docker lets you run **your game's existing server binary** as a container - including headless Unity and Unreal Engine builds.

Both Unity and Unreal support headless (no display, no graphics) server builds that run as standalone Linux executables. You package that executable into a Docker image, and Conductor can spin it up on demand just like any other container.

This means:

- Your game server is written in C# (Unity) or C++ (Unreal), as it always was
- Your backend is responsible only for orchestration - starting servers, routing players, and stopping servers when sessions end
- The two concerns are completely separated; the backend does not need to know anything about game-specific logic

The backend's job is not to run the game. Its job is to tell Conductor when to start a server, wait for it to be ready, and hand players the address to connect to.

---

## Predictable, Idempotent Environments

A Docker image is immutable. Every time you start a container from the same image, you get the same environment. There are no "works on my machine" problems, no dependency drift between instances, and no state left over from a previous run.

This makes containers well-suited to game server instances:

1. A session begins. Conductor starts a container from your game server image.
2. The container initializes, loads its configuration, and begins accepting player connections.
3. The session ends. The container is stopped and discarded.
4. The next session starts a fresh container from the same image, in the same known state.

Each container instance has a finite task. It runs for as long as the session lasts, then it is terminated. Nothing persists inside the container between runs - any state that needs to survive a session (player progress, match results) must be written out to an external database or API before the container exits.

---

## Linux-Based Images

Docker images used for game servers are almost always Linux-based. Linux containers are smaller, start faster, and are far more cost-efficient to run at scale than Windows containers. The major cloud providers and fleet hosting platforms (including EdgeGap and AWS ECS) are optimized for Linux workloads.

Importantly, game server containers do **not** use graphics hardware. A headless server build renders nothing - it only simulates the game world and processes player inputs. This means you do not need expensive GPU instances; a standard compute instance (CPU only) is sufficient for most game server workloads.

When building a headless Unity or Unreal server for Docker:

- Target the **Linux/Server** build platform in your engine's build settings
- Ensure all graphics-related subsystems are disabled or headless-safe
- The resulting binary is a standard Linux executable that Docker can run directly

---

## Configuring Servers with Environment Variables

Because each container starts from the same immutable image, you need a way to vary its behavior at runtime - for example, specifying the map to load, the maximum player count, the session ID, or the backend URL to call when the match ends.

The standard mechanism for this is **environment variables**. When Conductor starts a container via `OrchestrationService.execute()`, the `JobRequest` carries an `environment` map of key-value pairs that are injected into the container at startup. Your game server reads these variables on boot and configures itself accordingly.

```
MATCH_ID=abc-123
MAP_NAME=desert_canyon
MAX_PLAYERS=64
BACKEND_URL=https://api.mygame.com
BACKEND_SECRET=<shared secret or token>
```

This keeps the image generic and reusable. The same image handles any map, any session size, and any configuration - the values just change per launch. The backend URL and any security credentials required to authenticate with the backend should always be passed this way, never baked into the image itself.

### Bi-Directional Communication and Purpose-Built Backend Code

Once a session is running, the game server and the Namazu Elements backend communicate in both directions. The backend starts the server and passes it configuration; the server calls back to the backend to report events and request authoritative decisions.

This bi-directional communication typically requires **purpose-built backend code** - custom API endpoints and service functions written specifically for your game and called by the game server at runtime. Examples include:

- Notifying the backend that the match has started and players may be dispatched
- Requesting a loot table or item drop decision from the backend
- Reporting player kills, scores, or progression events
- Signaling match end so the backend can record results and release the server

The backend URL and credentials to call these endpoints are injected as environment variables at launch, so each server instance knows exactly where and how to reach home.

### Security and Anti-Cheat

Running game logic on a server you control - rather than on the player's machine - is one of the most effective tools available for preventing cheating. A player can modify their own client, intercept their own network traffic, and manipulate anything running on their own hardware. They cannot do any of those things to a server running in your infrastructure.

By ensuring that all **critical game logic executes on the server**, you remove it from the reach of cheaters entirely. The authoritative state of the game world, loot drops, damage calculations, matchmaking decisions, and progression updates all happen inside a container you own and control. The client presents the result; it does not determine it.

The backend adds another layer of authority. When the game server calls your Namazu Elements backend to report an event or request a decision, the backend can validate, audit, and enforce rules that neither the client nor the server can bypass on their own. Together, the game server and the backend form a chain of trust that keeps critical logic off the client and in verified infrastructure.

This approach does not eliminate all forms of cheating, but it significantly raises the bar and removes entire categories of exploit that would otherwise be trivially easy to execute against client-authoritative games.

---

## Summary

| Concept | Key Point |
|---|---|
| Containers | Lightweight, isolated, start in seconds |
| Images | Immutable snapshots; every instance starts identically |
| Game server support | Run headless Unity or Unreal builds without rewriting in the backend's language |
| Lifecycle | Start on demand, run for the session, terminate cleanly |
| OS | Linux-based; no graphics hardware required |
| Configuration | Environment variables injected at launch via `JobRequest` |
| Credentials | Backend URL and secrets passed as environment variables, never baked into the image |
| Communication | Bi-directional; backend starts the server, server calls purpose-built backend endpoints |
| Security | Critical game logic runs server-side, out of reach of client-side cheating |

Docker is the packaging layer that makes on-demand game server orchestration practical. Namazu Conductor manages the lifecycle; Docker ensures that every instance of your server starts predictably, runs cleanly, and leaves nothing behind.
