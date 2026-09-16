package dev.getelements.conductor.edgegap.service

import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.exception.StdioUnavailableException
import org.testng.Assert.assertThrows
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

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
 *   -e NAMAZU_CONDUCTOR_STDIO_TOKEN=test-token \
 *   namazu-stdio-bridge:it
 * mvn verify -pl edgegap -Dit.test=StdioBridgeClientIT
 * ```
 * Override `STDIO_BRIDGE_IT_HOST`/`STDIO_BRIDGE_IT_PORT`/`STDIO_BRIDGE_IT_TOKEN` (defaults
 * `localhost`/`10080`/`test-token`, matching the command above) to point at a differently-configured
 * bridge. The bridge is restarted per run since `stdio-bridge-toy-entrypoint.sh` exits after the
 * "quit" line this test sends.
 */
class StdioBridgeClientIT {

    private fun host() = System.getenv("STDIO_BRIDGE_IT_HOST")?.takeIf { it.isNotBlank() } ?: "localhost"

    private fun port() = System.getenv("STDIO_BRIDGE_IT_PORT")?.takeIf { it.isNotBlank() }?.toInt() ?: 10080

    private fun token() = System.getenv("STDIO_BRIDGE_IT_TOKEN")?.takeIf { it.isNotBlank() } ?: "test-token"

    // Explicit priority: roundTripsStdinStdoutStderr sends "quit", which kills the bridge's child
    // process and, with it, the whole container (--rm). If connectFailsWithWrongToken ran after
    // that, it would "pass" trivially because the container is gone, not because auth rejected it.
    @Test(priority = 0)
    fun connectFailsWithWrongToken() {
        assertThrows(StdioUnavailableException::class.java) {
            StdioBridgeClient.connect(host(), port(), "", "definitely-not-the-right-token")
        }
    }

    @Test(priority = 1)
    fun roundTripsStdinStdoutStderr() {
        val stdio = connectWithRetry()
        val stdoutReader = BufferedReader(stdio.stdout.reader())
        val stderrReader = BufferedReader(stdio.stderr.reader())

        try {
            // The bridge's ring buffer replays recent output on connect, so the toy entrypoint's
            // startup banner (written before this test connects) is still visible here.
            assertLine(stdoutReader, "stdout-startup-banner")
            assertLine(stderrReader, "stderr-startup-banner")

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

    // The self-restarting bridge container in CI (see release.yaml/edgegap-it.yaml) is a tight
    // `docker run --rm` loop with no guaranteed restart latency. In release.yaml specifically, a
    // full reactor build also runs minikube concurrently — under that combined load the restart
    // can occasionally lag past its usual near-instant respawn. Tolerate a brief window here rather
    // than fail on a container that is merely still coming back up.
    private fun connectWithRetry(): JobStdio {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (true) {
            try {
                return StdioBridgeClient.connect(host(), port(), "", token())
            } catch (e: StdioUnavailableException) {
                if (System.currentTimeMillis() >= deadline) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun assertLine(reader: BufferedReader, expected: String) {
        val line = reader.readLine()
        assertTrue(line == expected, "Expected '$expected', got '$line'")
    }

}
