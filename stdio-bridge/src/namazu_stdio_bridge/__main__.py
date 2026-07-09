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
import signal as signal_module
import sys

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

logger = logging.getLogger("namazu_stdio_bridge")

DEFAULT_ENTRYPOINT_CANDIDATES = (
    "/docker-entrypoint.sh:/usr/local/bin/docker-entrypoint.sh:/app/docker-entrypoint.sh"
)


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


class StdioBridge:
    """Fans a child process's stdout/stderr out to connected WebSocket clients, and forwards
    incoming WebSocket messages on the stdin endpoint to the process's stdin."""

    def __init__(self, process: asyncio.subprocess.Process, buffer_size: int):
        self.process = process
        self.buffer_size = buffer_size
        self.stdout_clients: set[ServerConnection] = set()
        self.stderr_clients: set[ServerConnection] = set()

    async def pump_stdin(self, ws: ServerConnection) -> None:
        try:
            async for message in ws:
                data = message if isinstance(message, (bytes, bytearray)) else message.encode()
                if self.process.stdin is not None and not self.process.stdin.is_closing():
                    self.process.stdin.write(data)
                    await self.process.stdin.drain()
        except ConnectionClosed:
            pass

    async def pump_output(self, stream: asyncio.StreamReader | None, clients: set[ServerConnection]) -> None:
        if stream is None:
            return
        while True:
            chunk = await stream.read(self.buffer_size)
            if not chunk:
                return
            for ws in list(clients):
                try:
                    await ws.send(chunk)
                except ConnectionClosed:
                    clients.discard(ws)

    async def register_output_client(self, ws: ServerConnection, clients: set[ServerConnection]) -> None:
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

    base = resolve_base_path()
    entrypoint = resolve_entrypoint()
    buffer_size = resolve_buffer_size()
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

    bridge = StdioBridge(process, buffer_size)
    stdout_task = asyncio.create_task(bridge.pump_output(process.stdout, bridge.stdout_clients))
    stderr_task = asyncio.create_task(bridge.pump_output(process.stderr, bridge.stderr_clients))
    exit_task = asyncio.create_task(bridge.wait_and_close())

    stdin_path = f"{base}/0"
    stdout_path = f"{base}/1"
    stderr_path = f"{base}/2"

    async def handler(ws: ServerConnection) -> None:
        path = ws.request.path if ws.request is not None else ""
        if path == stdin_path:
            await bridge.pump_stdin(ws)
        elif path == stdout_path:
            await bridge.register_output_client(ws, bridge.stdout_clients)
        elif path == stderr_path:
            await bridge.register_output_client(ws, bridge.stderr_clients)
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
