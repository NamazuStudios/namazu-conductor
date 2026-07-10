package dev.getelements.conductor.edgegap.service

import dev.getelements.conductor.JobStdio
import dev.getelements.conductor.exception.StdioUnavailableException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Connects to a `namazu-stdio-bridge` sidecar's three WebSocket endpoints (see
 * `stdio-bridge/README.md` for the wire protocol) and bundles them into a [JobStdio]. Used by
 * providers whose platform has no native container stdio API (EdgeGap; ECS follows the same
 * pattern) and instead relies on the bridge running inside the workload's own container image.
 */
internal object StdioBridgeClient {

    private const val CONNECT_TIMEOUT_SECONDS = 10L

    /**
     * @throws StdioUnavailableException if any of the three endpoints can't be reached — the bridge
     *   isn't present in the image, its port isn't mapped/reachable, or the workload isn't up yet
     */
    fun connect(host: String, port: Int, basePath: String): JobStdio {
        val base = if (basePath.isNotEmpty() && !basePath.startsWith("/")) "/$basePath" else basePath
        fun uri(channel: Int) = URI.create("ws://$host:$port$base/$channel")

        val client = HttpClient.newHttpClient()
        val stdoutSink = QueueInputStream()
        val stderrSink = QueueInputStream()

        try {
            val stdinWs = connectPlain(client, uri(0))
            val stdoutWs = connectOutput(client, uri(1), stdoutSink)
            val stderrWs = connectOutput(client, uri(2), stderrSink)

            return JobStdio(
                stdin = WebSocketOutputStream(stdinWs),
                stdout = stdoutSink,
                stderr = stderrSink,
                onClose = {
                    stdinWs.abort()
                    stdoutWs.abort()
                    stderrWs.abort()
                }
            )
        } catch (e: Exception) {
            throw StdioUnavailableException(
                "Could not reach namazu-stdio-bridge at $host:$port$base — is the bridge present in " +
                    "the workload's image, with its port mapped and reachable? See stdio-bridge/README.md.",
                e
            )
        }
    }

    private fun connectPlain(client: HttpClient, uri: URI): WebSocket =
        client.newWebSocketBuilder()
            .buildAsync(uri, object : WebSocket.Listener {})
            .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private fun connectOutput(client: HttpClient, uri: URI, sink: QueueInputStream): WebSocket {
        val listener = object : WebSocket.Listener {

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                // The buffer is only guaranteed valid for the duration of this callback, so copy it
                // out before returning rather than just duplicate()-ing the shared backing array.
                val copy = ByteBuffer.allocate(data.remaining())
                copy.put(data)
                copy.flip()
                sink.offer(copy)
                webSocket.request(1)
                return null
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                sink.offer(ByteBuffer.wrap(data.toString().toByteArray()))
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                sink.signalEnd()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                sink.signalEnd()
            }

        }
        return client.newWebSocketBuilder().buildAsync(uri, listener).get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

}

/**
 * A blocking [InputStream] fed by [offer] from a WebSocket listener callback; [signalEnd] marks EOF.
 */
private class QueueInputStream : InputStream() {

    private val queue = LinkedBlockingQueue<ByteBuffer>()
    private var current: ByteBuffer? = null
    private var ended = false

    fun offer(data: ByteBuffer) {
        if (data.hasRemaining()) queue.put(data)
    }

    fun signalEnd() = queue.put(EOF_MARKER)

    private fun fill(): Boolean {
        if (ended) return false
        if (current?.hasRemaining() == true) return true
        val next = queue.take()
        if (next === EOF_MARKER) {
            ended = true
            return false
        }
        current = next
        return true
    }

    override fun read(): Int {
        if (!fill()) return -1
        return current!!.get().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, current!!.remaining())
        current!!.get(b, off, n)
        return n
    }

    override fun close() {
        ended = true
        queue.put(EOF_MARKER)
    }

    companion object {
        private val EOF_MARKER = ByteBuffer.allocate(0)
    }

}

/**
 * A blocking [OutputStream] that sends each write as a binary WebSocket frame.
 */
private class WebSocketOutputStream(private val webSocket: WebSocket) : OutputStream() {

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        webSocket.sendBinary(ByteBuffer.wrap(b, off, len), true).join()
    }

    override fun close() {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").join()
    }

}
