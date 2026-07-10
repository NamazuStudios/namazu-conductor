package dev.getelements.conductor.edgegap.service

import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.BufferedReader

/**
 * Integration test for [StdioBridgeClient] — the WebSocket client `EdgeGapOrchestrationService`
 * (and, following the same pattern, the ECS provider) uses to implement `streamStdio`. Exercises the
 * client against a real `namazu-stdio-bridge` container, independent of any EdgeGap/ECS account.
 *
 * **Prerequisite:** a `namazu-stdio-bridge` container must already be running and reachable before
 * `mvn verify -pl edgegap` — this test does not provision it. Locally:
 * ```
 * docker build -t namazu-stdio-bridge:it ../stdio-bridge
 * docker run -d --rm -p 10080:10080 \
 *   -v "$(pwd)/src/test/resources/stdio-bridge-toy-entrypoint.sh:/toy-entrypoint.sh:ro" \
 *   -e NAMAZU_CONDUCTOR_STDIO_ENTRYPOINT=/toy-entrypoint.sh \
 *   namazu-stdio-bridge:it
 * mvn verify -pl edgegap -Dit.test=StdioBridgeClientIT
 * ```
 * Override `STDIO_BRIDGE_IT_HOST`/`STDIO_BRIDGE_IT_PORT` (default `localhost`/`10080`) to point at a
 * differently-hosted bridge. The bridge is restarted per run since `stdio-bridge-toy-entrypoint.sh`
 * exits after the "quit" line this test sends.
 */
class StdioBridgeClientIT {

    @Test
    fun roundTripsStdinStdoutStderr() {
        val host = System.getenv("STDIO_BRIDGE_IT_HOST")?.takeIf { it.isNotBlank() } ?: "localhost"
        val port = System.getenv("STDIO_BRIDGE_IT_PORT")?.takeIf { it.isNotBlank() }?.toInt() ?: 10080

        val stdio = StdioBridgeClient.connect(host, port, "")
        val stdoutReader = BufferedReader(stdio.stdout.reader())
        val stderrReader = BufferedReader(stdio.stderr.reader())

        try {
            // The toy entrypoint's startup banner is not asserted on: like `kubectl attach`, the
            // bridge only streams output produced after a client connects, and there's an inherent
            // race between container start and this test's connect() — no delivery guarantee exists
            // for anything written before we're subscribed.

            stdio.stdin.write("hello\n".toByteArray())
            assertLine(stdoutReader, "echo:hello")
            assertLine(stderrReader, "stderr-echo:hello")

            stdio.stdin.write("quit\n".toByteArray())
            assertLine(stdoutReader, "echo:quit")
            assertLine(stderrReader, "stderr-echo:quit")

            // The toy process has now exited (exit code 7); both streams should reach EOF.
            assertTrue(stdoutReader.readLine() == null, "Expected stdout EOF after process exit")
            assertTrue(stderrReader.readLine() == null, "Expected stderr EOF after process exit")
        } finally {
            stdio.close()
        }
    }

    private fun assertLine(reader: BufferedReader, expected: String) {
        val line = reader.readLine()
        assertTrue(line == expected, "Expected '$expected', got '$line'")
    }

}
