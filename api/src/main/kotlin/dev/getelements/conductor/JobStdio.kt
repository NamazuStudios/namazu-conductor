package dev.getelements.conductor

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Bidirectional process I/O for a running execution. [stdin] accepts writes forwarded to the
 * process's standard input; [stdout] and [stderr] are separate live streams. Closing this closes
 * all three and releases the underlying connection.
 */
class JobStdio(
    val stdin: OutputStream,
    val stdout: InputStream,
    val stderr: InputStream,
    private val onClose: () -> Unit = {},

    /**
     * Resizes the remote pty, if this session was opened with a tty attached (see
     * [dev.getelements.conductor.JobRequest.tty]) and the provider supports live resizing.
     * `null` when no pty is attached or the provider doesn't support resizing.
     */
    val resize: ((cols: Int, rows: Int) -> Unit)? = null
) : Closeable {

    override fun close() {
        stdin.close()
        stdout.close()
        stderr.close()
        onClose()
    }

}