# Namazu Conductor -Overview

## What Is Orchestration?

Game server orchestration is the process by which a backend system provisions, monitors, and tears down game servers in response to player activity. Rather than maintaining a fixed pool of always-on servers, an orchestrated system starts servers when they are needed and stops them when they are not -matching infrastructure to demand in real time.

Namazu Conductor provides a unified orchestration API for [Namazu Elements](https://namazustudios.com/docs)-based games. It abstracts the details of the underlying hosting provider behind a common interface, so game backend code can request a server without knowing whether it will run on AWS ECS, EdgeGap, or another platform.

---

## The Role of the Backend

A multiplayer game is rarely a single server. It is a collection of servers working in concert -each with a distinct responsibility -coordinated by a central authority.

At the heart of this architecture is the **game backend**: the authoritative source of truth that directs and controls all other servers in response to in-game events. The backend decides what to start, when to start it, who connects to it, and when to shut it down.

Player actions drive these decisions. When a player manually starts a private game, joins a matchmaking queue, enters an instanced dungeon, or triggers any event that requires server-side computation, it is the backend that receives that signal and orchestrates the response. The backend may:

- Query available server profiles from Conductor
- Launch a new server instance via `OrchestrationService.execute()`
- Poll the job's `JobStatus` until it reaches `RUNNING`
- Return the server's endpoint to the client or matchmaker
- Stop the server when the session ends

Conductor handles the provider-specific mechanics of all of this, while the backend focuses on game logic.

---

## Choosing the Right Provider

Not all game servers have the same lifecycle or cost profile. Conductor supports multiple provider types precisely because different workloads call for different infrastructure.

### Long-Running Servers -AWS ECS

AWS ECS (Elastic Container Service) is well-suited to servers that must exist indefinitely or run for extended periods. ECS tasks can be configured to restart automatically on failure, integrate with persistent storage, and run within a known VPC with stable addressing.

A canonical example is a **persistent zone in an MMORPG**. The Ironforge district, the open-world PvP zone, the auction house server -these are always on. Players connect and disconnect, but the world continues to exist. Spinning these up and down per session would be impractical; they need to run continuously, reliably, and with infrastructure that supports long-term state.

ECS is also the right choice when the server needs access to other AWS infrastructure - a game server that queries RDS for player data, reads from ElastiCache, or writes to S3 benefits from running within the same VPC as those services.

ECS is the right choice when:
- The server must persist beyond a single session
- Uptime and automatic recovery are important
- The workload justifies a dedicated, continuously running container
- The server needs direct access to other AWS services within a VPC

### On-Demand Fleet Servers -EdgeGap

Fleet computing providers such as EdgeGap are optimized for cost-effective, low-latency, on-demand servers. Servers are started when a session begins and terminated when it ends. The provider manages the global fleet, and the orchestrator selects the deployment region closest to the players.

This model is ideal for **session-based games** -games where each match is its own self-contained server with a finite task. Consider:

- **Fortnite** or **PUBG** -100 players drop into a map; one server runs the match from start to finish; the server is destroyed when the last player dies or the match concludes.
- **ARC Raiders** -a squad assembles, enters a raid, and the session server exists only for the duration of that raid.

In these games, the matchmaking queue assembles a group of players, the backend requests a server via Conductor, and once `JobStatus.RUNNING` is confirmed the players are dispatched to it. When the session ends, the job is stopped and the infrastructure is released. The per-minute billing model of fleet providers makes this economically viable at scale -you pay only for the time players are actually in a match.

Fleet computing is the right choice when:
- Sessions have a clear start and end
- Cost efficiency at scale is a priority
- Low-latency global placement matters
- There is no need for persistent server-side state between sessions

### Quick Reference

| Workload | Recommended Provider |
|---|---|
| MMORPG persistent zone (always on, stateful) | ECS |
| Private guild hall or housing instance (long-running, per-guild) | ECS |
| Battle royale match server (session-scoped, ephemeral) | EdgeGap |
| Co-op raid or instanced dungeon (session-scoped, ephemeral) | EdgeGap |

---

## Namazu Conductor as a Unified Interface

A game backend that wants to support multiple hosting providers -or that may want to switch providers in the future -should not be coupled to any one provider's SDK. Each provider has its own API, authentication model, deployment concepts, and lifecycle primitives. Writing directly against all of them produces fragile, expensive-to-maintain code.

Namazu Conductor solves this by presenting a single `OrchestrationService` interface regardless of the underlying provider. The backend code calls `execute()` to start a server, polls `JobStatus` to wait for it to be ready, and calls `stop()` when the session ends -using identical code whether the job runs on ECS, EdgeGap, or any future provider.

This abstraction also enables the backend to **scale server infrastructure dynamically in response to player demand**. As players fill matchmaking queues, the backend requests more servers; as queues drain, no further servers are started and running ones are stopped at session end. Because Conductor's on-demand providers bill per second of usage, the total infrastructure cost closely tracks the number of active players rather than a fixed capacity ceiling.

The result is a system that grows when the game is busy and shrinks when it is not -without the backend needing to know which cloud or fleet provider is doing the work.

---

## Summary

| Concern | ECS | EdgeGap (Fleet) |
|---|---|---|
| Lifecycle | Long-running, persistent | Session-scoped, ephemeral |
| Cost model | Always-on, reserved | Per-second, on-demand |
| Example use case | MMORPG persistent zones | Battle royale match servers |
| State | Stateful, durable | Stateless or session-local |
| Placement | Regional, fixed VPC | Global, player-proximity |

Namazu Conductor exposes both models through the same `OrchestrationService` interface. The backend selects a `JobProfile` that corresponds to the appropriate provider and workload type, and Conductor handles the rest.