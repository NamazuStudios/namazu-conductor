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
    private val onClose: () -> Unit = {}
) : Closeable {

    override fun close() {
        stdin.close()
        stdout.close()
        stderr.close()
        onClose()
    }

}