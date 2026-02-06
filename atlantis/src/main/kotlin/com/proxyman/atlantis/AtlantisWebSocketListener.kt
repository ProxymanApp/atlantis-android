package com.proxyman.atlantis

import okhttp3.Response as OkHttpResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID

/**
 * OkHttp WebSocketListener wrapper that captures WebSocket traffic and forwards it to Proxyman.
 *
 * - Incoming messages are captured via WebSocketListener callbacks.
 * - Outgoing messages are captured via a proxy WebSocket passed to the user's listener.
 *
 * Important:
 * If the app sends messages using the WebSocket instance returned by OkHttpClient.newWebSocket(),
 * those sends are NOT interceptable via OkHttp APIs. For outgoing capture, the app should send
 * using the WebSocket instance received in onOpen/onMessage callbacks (the proxy).
 */
class AtlantisWebSocketListener internal constructor(
    private val userListener: WebSocketListener
) : WebSocketListener() {

    internal val connectionId: String = UUID.randomUUID().toString()

    @Volatile
    private var proxyWebSocket: WebSocket? = null

    private fun getOrCreateProxyWebSocket(webSocket: WebSocket): WebSocket {
        val existing = proxyWebSocket
        if (existing != null) return existing
        return AtlantisProxyWebSocket(webSocket, connectionId).also { proxyWebSocket = it }
    }

    override fun onOpen(webSocket: WebSocket, response: OkHttpResponse) {
        val proxy = getOrCreateProxyWebSocket(webSocket)
        try {
            Atlantis.onWebSocketOpen(
                id = connectionId,
                request = webSocket.request(),
                response = response
            )
        } catch (_: Exception) {
            // Best effort only
        }
        userListener.onOpen(proxy, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val proxy = getOrCreateProxyWebSocket(webSocket)
        try {
            Atlantis.onWebSocketReceiveText(id = connectionId, text = text)
        } catch (_: Exception) {
        }
        userListener.onMessage(proxy, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val proxy = getOrCreateProxyWebSocket(webSocket)
        try {
            Atlantis.onWebSocketReceiveBinary(id = connectionId, bytes = bytes.toByteArray())
        } catch (_: Exception) {
        }
        userListener.onMessage(proxy, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        val proxy = getOrCreateProxyWebSocket(webSocket)
        try {
            Atlantis.onWebSocketClosing(id = connectionId, code = code, reason = reason)
        } catch (_: Exception) {
        }
        userListener.onClosing(proxy, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        val proxy = getOrCreateProxyWebSocket(webSocket)
        try {
            Atlantis.onWebSocketClosed(id = connectionId, code = code, reason = reason)
        } catch (_: Exception) {
        }
        userListener.onClosed(proxy, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: OkHttpResponse?) {
        val proxy = getOrCreateProxyWebSocket(webSocket)
        try {
            Atlantis.onWebSocketFailure(id = connectionId, t = t, response = response)
        } catch (_: Exception) {
        }
        userListener.onFailure(proxy, t, response)
    }

    private class AtlantisProxyWebSocket(
        private val delegate: WebSocket,
        private val id: String
    ) : WebSocket {

        override fun request(): okhttp3.Request = delegate.request()

        override fun queueSize(): Long = delegate.queueSize()

        override fun send(text: String): Boolean {
            try {
                Atlantis.onWebSocketSendText(id = id, text = text)
            } catch (_: Exception) {
            }
            return delegate.send(text)
        }

        override fun send(bytes: ByteString): Boolean {
            try {
                Atlantis.onWebSocketSendBinary(id = id, bytes = bytes.toByteArray())
            } catch (_: Exception) {
            }
            return delegate.send(bytes)
        }

        override fun close(code: Int, reason: String?): Boolean {
            try {
                Atlantis.onWebSocketClosing(id = id, code = code, reason = reason)
            } catch (_: Exception) {
            }
            return delegate.close(code, reason)
        }

        override fun cancel() {
            delegate.cancel()
        }
    }
}

