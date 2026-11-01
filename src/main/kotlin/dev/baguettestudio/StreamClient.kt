package dev.baguettestudio

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/** One WebSocket to `/simulators/:udid/stream?format=mjpeg`. Frames in (JPEG), JSON input out. */
class StreamClient(
    baseUrl: String,
    udid: String,
    private val onFrame: (BufferedImage) -> Unit,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "baguette-ws-send").apply { isDaemon = true } }
    private val sendBusy = AtomicBoolean(false)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false

    init {
        val uri = URI.create(baseUrl.replaceFirst("http", "ws") + "/simulators/$udid/stream?format=mjpeg")
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().newWebSocketBuilder()
            .buildAsync(uri, Listener())
            .whenComplete { w, err ->
                if (err != null) onStatus("Stream connect failed: ${err.cause?.message ?: err.message}") else ws = w
            }
    }

    /** Fire-and-forget. [droppable] messages (touch moves) are skipped while a send is in flight. */
    fun send(json: String, droppable: Boolean = false) {
        val w = ws ?: return
        if (droppable && sendBusy.get()) return
        sender.execute {
            sendBusy.set(true)
            try { w.sendText(json, true).join() } catch (_: Exception) { } finally { sendBusy.set(false) }
        }
    }

    override fun close() {
        closed = true
        ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye")
        ws?.abort()
        sender.shutdownNow()
    }

    private inner class Listener : WebSocket.Listener {
        private val buf = ByteArrayOutputStream()

        override fun onOpen(webSocket: WebSocket) {
            onStatus("Waiting for first frame…")
            webSocket.request(1)
        }

        override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
            val bytes = ByteArray(data.remaining()).also { data.get(it) }
            buf.write(bytes)
            if (last) {
                val jpeg = buf.toByteArray()
                buf.reset()
                runCatching { ImageIO.read(jpeg.inputStream()) }.getOrNull()?.let(onFrame)
            }
            webSocket.request(1)
            return null
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val s = data.toString()
            if (s.contains("\"ok\":false")) onStatus("Baguette: $s")
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            if (!closed) onStatus("Stream closed ($statusCode) $reason")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (!closed) onStatus("Stream error: ${error.message}")
        }
    }
}
