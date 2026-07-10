"""namazu-stdio-bridge entrypoint.

Discovers a container's real entrypoint, execs it with argv forwarded unchanged, and serves its
stdin/stdout/stderr over three WebSocket endpoints so platforms with no native container stdio
access (EdgeGap, ECS) can offer the same bidirectional stdio namazu-conductor gets natively from
Kubernetes via `kubectl attach`.

See README.md for the wire protocol and configuration environment variables.
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import signal as signal_module
import sys

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

logger = logging.getLogger("namazu_stdio_bridge")

DEFAULT_ENTRYPOINT_CANDIDATES = (
    "/docker-entrypoint.sh:/usr/local/bin/docker-entrypoint.sh:/app/docker-entrypoint.sh"
)

_SIZE_PATTERN = re.compile(r"(\d+)\s*([kKmM]?)")


def parse_size(value: str) -> int:
    """Parses a byte-count string like "4096", "16k", or "1M" (case-insensitive; k=KiB, m=MiB)."""
    match = _SIZE_PATTERN.fullmatch(value.strip())
    if not match:
        raise ValueError(f"Invalid buffer size: {value!r}")
    number = int(match.group(1))
    multiplier = {"": 1, "k": 1024, "m": 1024 * 1024}[match.group(2).lower()]
    return number * multiplier


def resolve_base_path() -> str:
    return os.environ.get("NAMAZU_CONDUCTOR_STDIO_URI", "/").rstrip("/")


def resolve_entrypoint() -> str:
    candidates = os.environ.get("NAMAZU_CONDUCTOR_STDIO_ENTRYPOINT", DEFAULT_ENTRYPOINT_CANDIDATES)
    for candidate in candidates.split(os.pathsep):
        candidate = candidate.strip()
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    raise FileNotFoundError(f"No executable entrypoint found among candidates: {candidates!r}")


def resolve_buffer_size() -> int:
    return int(os.environ.get("NAMAZU_CONDUCTOR_STDIO_BUFFER_SIZE", "4096"))


def resolve_port() -> int:
    return int(os.environ.get("NAMAZU_CONDUCTOR_STDIO_PORT", "10080"))


def resolve_stdout_ring_size() -> int:
    return parse_size(os.environ.get("NAMAZU_CONDUCTOR_STDIO_STDOUT_BUFFER_SIZE", "16k"))


def resolve_stderr_ring_size() -> int:
    return parse_size(os.environ.get("NAMAZU_CONDUCTOR_STDIO_STDERR_BUFFER_SIZE", "4096"))


def resolve_token() -> str:
    """Required — refuses to start without it, since the stdio endpoints would otherwise be
    reachable, with no authentication at all, by anyone who can reach the port."""
    token = os.environ.get("NAMAZU_CONDUCTOR_STDIO_TOKEN")
    if not token:
        raise RuntimeError(
            "NAMAZU_CONDUCTOR_STDIO_TOKEN is required — namazu-stdio-bridge refuses to start "
            "without an access token configured."
        )
    return token


class RingBuffer:
    """Fixed-capacity byte ring buffer: retains only the most recently appended `capacity` bytes,
    so a client connecting late can still replay recent (not full) history. Capacity 0 disables it."""

    def __init__(self, capacity: int):
        self.capacity = capacity
        self._data = bytearray()

    def append(self, chunk: bytes) -> None:
        if self.capacity <= 0:
            return
        self._data.extend(chunk)
        overflow = len(self._data) - self.capacity
        if overflow > 0:
            del self._data[:overflow]

    def snapshot(self) -> bytes:
        return bytes(self._data)


class StdioBridge:
    """Fans a child process's stdout/stderr out to connected WebSocket clients, and forwards
    incoming WebSocket messages on the stdin endpoint to the process's stdin."""

    def __init__(
        self,
        process: asyncio.subprocess.Process,
        buffer_size: int,
        stdout_ring_size: int,
        stderr_ring_size: int,
    ):
        self.process = process
        self.buffer_size = buffer_size
        self.stdout_clients: set[ServerConnection] = set()
        self.stderr_clients: set[ServerConnection] = set()
        self.stdout_ring = RingBuffer(stdout_ring_size)
        self.stderr_ring = RingBuffer(stderr_ring_size)

    async def pump_stdin(self, ws: ServerConnection) -> None:
        try:
            async for message in ws:
                data = message if isinstance(message, (bytes, bytearray)) else message.encode()
                if self.process.stdin is not None and not self.process.stdin.is_closing():
                    self.process.stdin.write(data)
                    await self.process.stdin.drain()
        except ConnectionClosed:
            pass

    async def pump_output(
        self,
        stream: asyncio.StreamReader | None,
        clients: set[ServerConnection],
        ring: RingBuffer,
    ) -> None:
        if stream is None:
            return
        while True:
            chunk = await stream.read(self.buffer_size)
            if not chunk:
                return
            ring.append(chunk)
            for ws in list(clients):
                try:
                    await ws.send(chunk)
                except ConnectionClosed:
                    clients.discard(ws)

    async def register_output_client(
        self,
        ws: ServerConnection,
        clients: set[ServerConnection],
        ring: RingBuffer,
    ) -> None:
        # Replay recent history before subscribing to live output, so a client that connects after
        # the process has already produced output isn't left with nothing until the next chunk.
        snapshot = ring.snapshot()
        if snapshot:
            await ws.send(snapshot)
        clients.add(ws)
        try:
            await ws.wait_closed()
        finally:
            clients.discard(ws)

    async def close_output_clients(self, code: int, reason: str) -> None:
        for clients in (self.stdout_clients, self.stderr_clients):
            for ws in list(clients):
                try:
                    await ws.close(code=code, reason=reason)
                except Exception:
                    pass
            clients.clear()

    async def wait_and_close(self) -> None:
        returncode = await self.process.wait()
        if returncode < 0:
            # POSIX: a negative returncode means the process was killed by signal -returncode.
            try:
                term_signal = signal_module.Signals(-returncode).name
            except ValueError:
                term_signal = ""
            exit_code = 128 - returncode
        else:
            term_signal = ""
            exit_code = returncode

        close_code = 4000 + max(0, min(exit_code, 255))
        logger.info("process exited: exit_code=%s signal=%s close_code=%s", exit_code, term_signal, close_code)
        await self.close_output_clients(close_code, term_signal)


async def run() -> int:
    logging.basicConfig(level=os.environ.get("NAMAZU_CONDUCTOR_STDIO_LOG_LEVEL", "INFO"))

    # Resolved (and, for the token, validated) before spawning anything — a missing token is a
    # deployment misconfiguration that should fail loudly and immediately, not silently run unsecured.
    token = resolve_token()
    base = resolve_base_path()
    entrypoint = resolve_entrypoint()
    buffer_size = resolve_buffer_size()
    stdout_ring_size = resolve_stdout_ring_size()
    stderr_ring_size = resolve_stderr_ring_size()
    port = resolve_port()
    argv = sys.argv[1:]

    logger.info("resolved entrypoint=%s argv=%s port=%s base=%r", entrypoint, argv, port, base)

    process = await asyncio.create_subprocess_exec(
        entrypoint,
        *argv,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    bridge = StdioBridge(process, buffer_size, stdout_ring_size, stderr_ring_size)
    stdout_task = asyncio.create_task(bridge.pump_output(process.stdout, bridge.stdout_clients, bridge.stdout_ring))
    stderr_task = asyncio.create_task(bridge.pump_output(process.stderr, bridge.stderr_clients, bridge.stderr_ring))
    exit_task = asyncio.create_task(bridge.wait_and_close())

    stdin_path = f"{base}/0"
    stdout_path = f"{base}/1"
    stderr_path = f"{base}/2"
    expected_authorization = f"Bearer {token}"

    def authorized(ws: ServerConnection) -> bool:
        header = ws.request.headers.get("Authorization") if ws.request is not None else None
        return header == expected_authorization

    async def handler(ws: ServerConnection) -> None:
        if not authorized(ws):
            await ws.close(code=1008, reason="unauthorized")
            return
        path = ws.request.path if ws.request is not None else ""
        if path == stdin_path:
            await bridge.pump_stdin(ws)
        elif path == stdout_path:
            await bridge.register_output_client(ws, bridge.stdout_clients, bridge.stdout_ring)
        elif path == stderr_path:
            await bridge.register_output_client(ws, bridge.stderr_clients, bridge.stderr_ring)
        else:
            await ws.close(code=1008, reason=f"unknown endpoint: {path}")

    async with serve(handler, "0.0.0.0", port) as server:
        logger.info(
            "namazu-stdio-bridge listening on :%s (stdin=%s stdout=%s stderr=%s)",
            port, stdin_path, stdout_path, stderr_path,
        )
        await exit_task
        await asyncio.gather(stdout_task, stderr_task)
        server.close()
        await server.wait_closed()

    return process.returncode if process.returncode is not None else 1


def main() -> None:
    sys.exit(asyncio.run(run()))


if __name__ == "__main__":
    main()
