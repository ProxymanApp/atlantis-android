package com.proxyman.atlantis.sample

import com.proxyman.atlantis.Atlantis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object WebSocketTestController {

    private const val WS_URL = "wss://echo.websocket.org/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    private val _isTestRunning = MutableStateFlow(false)
    val isTestRunning: StateFlow<Boolean> = _isTestRunning.asStateFlow()

    private var job: Job? = null

    fun startAutoTest(client: OkHttpClient) {
        if (!isRunning.compareAndSet(false, true)) {
            log("WebSocket test is already running")
            return
        }

        _isTestRunning.value = true
        job = scope.launch {
            try {
                runTest(client)
            } finally {
                _isTestRunning.value = false
                isRunning.set(false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        isRunning.set(false)
        _isTestRunning.value = false
        log("WebSocket test stopped")
    }

    private suspend fun runTest(client: OkHttpClient) {
        log("Connecting to $WS_URL")

        val wsOpen = CompletableDeferred<WebSocket>()
        val wsClosed = CompletableDeferred<Unit>()

        val userListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("onOpen: HTTP ${response.code}")
                wsOpen.complete(webSocket) // this is the Atlantis proxy WebSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                log("onMessage (text): $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                log("onMessage (binary): ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log("onClosing: code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("onClosed: code=$code reason=$reason")
                wsClosed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log("onFailure: ${t.message ?: t.javaClass.simpleName}")
                wsClosed.complete(Unit)
            }
        }

        val request = Request.Builder()
            .url(WS_URL)
            .build()

        val atlantisListener = Atlantis.wrapWebSocketListener(userListener)
        client.newWebSocket(request, atlantisListener)

        val ws = withTimeoutOrNull(10_000) { wsOpen.await() }
        if (ws == null) {
            log("Timeout: did not receive onOpen within 10s")
            return
        }

        delay(1000)
        val text = "Hello from Atlantis Android!"
        log("send (text): $text")
        ws.send(text)

        delay(1000)
        val json = """{"type":"test","timestamp":${System.currentTimeMillis()},"data":{"key":"value"}}"""
        log("send (json): $json")
        ws.send(json)

        delay(1000)
        val binaryPayload = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x10, 0x11, 0x12)
        log("send (binary): ${binaryPayload.size} bytes")
        ws.send(binaryPayload.toByteString())

        delay(1000)
        log("close: code=1000 reason=done")
        ws.close(1000, "done")

        withTimeoutOrNull(10_000) { wsClosed.await() }
        log("Test finished")
    }

    private fun log(message: String) {
        val ts = timestamp()
        val line = "[$ts] $message"
        _logText.value = buildString {
            val current = _logText.value
            if (current.isNotBlank()) {
                append(current)
                append("\n")
            }
            append(line)
        }
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date())
    }
}

