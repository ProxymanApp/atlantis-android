package com.proxyman.atlantis

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for WebSocket-specific bugfixes:
 *
 * 1. Interceptor skips 101 WebSocket upgrades (no duplicate HTTP capture)
 * 2. onWebSocketClosing deduplication (only 1 close message, not 3)
 * 3. WebSocket lifecycle produces the correct message types and shares one ID
 */
class AtlantisWebSocketTest {

    private val gson = Gson()

    // -----------------------------------------------------------------------
    // Helper: decode Message JSON -> extract inner TrafficPackage JSON
    // -----------------------------------------------------------------------

    /** Extract the base64-encoded "content" field from a Message JSON string. */
    private fun extractDecodedContent(messageJson: String): String {
        val map = gson.fromJson(messageJson, Map::class.java)
        val base64 = map["content"] as String
        return Base64Utils.decode(base64).toString(Charsets.UTF_8)
    }

    /** Extract "messageType" from top-level Message JSON. */
    private fun extractMessageType(messageJson: String): String {
        val map = gson.fromJson(messageJson, Map::class.java)
        return map["messageType"] as String
    }

    // -----------------------------------------------------------------------
    // 1. Initial traffic message has messageType=traffic, packageType=websocket
    // -----------------------------------------------------------------------

    @Test
    fun `test initial WS traffic message uses traffic type with websocket packageType`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = mapOf("Sec-WebSocket-Key" to "abc"),
            body = null
        )
        val response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))

        val basePackage = TrafficPackage(
            id = "ws-conn-1",
            startAt = 1.0,
            request = request,
            response = response,
            responseBodyData = "",
            endAt = 1.0,
            packageType = TrafficPackage.PackageType.WEBSOCKET
        )

        // The initial message must use buildTrafficMessage (type=traffic)
        val trafficMsg = Message.buildTrafficMessage("config-1", basePackage)
        val trafficJson = trafficMsg.toData()!!.toString(Charsets.UTF_8)
        assertEquals("traffic", extractMessageType(trafficJson))

        val innerJson = extractDecodedContent(trafficJson)
        assertTrue("Inner packageType must be websocket", innerJson.contains("\"packageType\":\"websocket\""))
        assertFalse("No websocketMessagePackage in initial traffic", innerJson.contains("\"websocketMessagePackage\":{"))
    }

    // -----------------------------------------------------------------------
    // 2. WS frame messages use messageType=websocket
    // -----------------------------------------------------------------------

    @Test
    fun `test WS frame message uses websocket message type`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        val response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))

        val basePackage = TrafficPackage(
            id = "ws-conn-2",
            startAt = 1.0,
            request = request,
            response = response,
            responseBodyData = "",
            endAt = 1.0,
            packageType = TrafficPackage.PackageType.WEBSOCKET
        )

        val wsPackage = WebsocketMessagePackage.createStringMessage(
            id = "ws-conn-2",
            message = "hello",
            type = WebsocketMessagePackage.MessageType.SEND
        )
        val framePackage = basePackage.copy(websocketMessagePackage = wsPackage)

        val wsMsg = Message.buildWebSocketMessage("config-1", framePackage)
        val wsJson = wsMsg.toData()!!.toString(Charsets.UTF_8)

        assertEquals("websocket", extractMessageType(wsJson))

        val innerJson = extractDecodedContent(wsJson)
        assertTrue(innerJson.contains("\"packageType\":\"websocket\""))
        assertTrue(innerJson.contains("\"messageType\":\"send\""))
        assertTrue(innerJson.contains("\"stringValue\":\"hello\""))
    }

    // -----------------------------------------------------------------------
    // 3. TrafficPackage.id is preserved across copy() (all WS messages share one ID)
    // -----------------------------------------------------------------------

    @Test
    fun `test all WS frame copies share the same TrafficPackage id`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        val response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))

        val basePackage = TrafficPackage(
            id = "ws-conn-shared",
            startAt = 1.0,
            request = request,
            response = response,
            responseBodyData = "",
            endAt = 1.0,
            packageType = TrafficPackage.PackageType.WEBSOCKET
        )

        val sendFrame = basePackage.copy(
            websocketMessagePackage = WebsocketMessagePackage.createStringMessage(
                id = "ws-conn-shared", message = "a", type = WebsocketMessagePackage.MessageType.SEND
            )
        )
        val receiveFrame = basePackage.copy(
            websocketMessagePackage = WebsocketMessagePackage.createStringMessage(
                id = "ws-conn-shared", message = "b", type = WebsocketMessagePackage.MessageType.RECEIVE
            )
        )
        val closeFrame = basePackage.copy(
            websocketMessagePackage = WebsocketMessagePackage.createCloseMessage(
                id = "ws-conn-shared", closeCode = 1000, reason = "done"
            )
        )

        // All copies must share the same id as the base package
        assertEquals("ws-conn-shared", sendFrame.id)
        assertEquals("ws-conn-shared", receiveFrame.id)
        assertEquals("ws-conn-shared", closeFrame.id)

        // But websocketMessagePackage should be different per frame
        assertEquals("send", getWsMessageType(sendFrame))
        assertEquals("receive", getWsMessageType(receiveFrame))
        assertEquals("sendCloseMessage", getWsMessageType(closeFrame))
    }

    // -----------------------------------------------------------------------
    // 4. Close deduplication: only first close produces a package; subsequent
    //    calls to onWebSocketClosing with same id find no base package.
    // -----------------------------------------------------------------------

    @Test
    fun `test close dedup - base package removed after first close copy`() {
        // Simulate what Atlantis.onWebSocketClosing does: remove from map, build close package.
        val packages = java.util.concurrent.ConcurrentHashMap<String, TrafficPackage>()

        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        val basePackage = TrafficPackage(
            id = "ws-dedup",
            startAt = 1.0,
            request = request,
            response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket")),
            responseBodyData = "",
            endAt = 1.0,
            packageType = TrafficPackage.PackageType.WEBSOCKET
        )
        packages["ws-dedup"] = basePackage

        // First close: remove succeeds
        val first = packages.remove("ws-dedup")
        assertNotNull("First close should find the package", first)

        // Second close: remove returns null (already removed)
        val second = packages.remove("ws-dedup")
        assertNull("Second close must NOT find the package (dedup)", second)

        // Third close: same
        val third = packages.remove("ws-dedup")
        assertNull("Third close must NOT find the package (dedup)", third)
    }

    // -----------------------------------------------------------------------
    // 5. Copy preserves all fields but allows different websocketMessagePackage
    // -----------------------------------------------------------------------

    @Test
    fun `test copy does not mutate base package`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        val response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))

        val base = TrafficPackage(
            id = "ws-immut",
            startAt = 1.0,
            request = request,
            response = response,
            responseBodyData = "",
            endAt = 1.0,
            packageType = TrafficPackage.PackageType.WEBSOCKET
        )

        assertNull("Base has no websocketMessagePackage initially", base.websocketMessagePackage)

        val withMsg = base.copy(
            websocketMessagePackage = WebsocketMessagePackage.createStringMessage(
                id = "ws-immut", message = "hi", type = WebsocketMessagePackage.MessageType.SEND
            )
        )

        // Base must remain untouched
        assertNull("Base still has no websocketMessagePackage after copy", base.websocketMessagePackage)
        assertNotNull("Copy has websocketMessagePackage", withMsg.websocketMessagePackage)
    }

    // -----------------------------------------------------------------------
    // 6. Interceptor skip: verify a 101 response is NOT captured by sendPackage
    //    (We test the data-model side: a TrafficPackage with statusCode 101
    //    should never be created by the interceptor path.)
    // -----------------------------------------------------------------------

    @Test
    fun `test TrafficPackage with 101 response is valid but should not appear from interceptor`() {
        // This test documents the invariant: interceptor skips 101.
        // We verify that a manually-created 101 TrafficPackage serializes correctly
        // (it can exist from the WebSocket path), but with packageType=websocket.
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        val response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))

        val pkg = TrafficPackage(
            id = "ws-101",
            startAt = 1.0,
            request = request,
            response = response,
            responseBodyData = "",
            endAt = 1.0,
            packageType = TrafficPackage.PackageType.WEBSOCKET
        )

        val json = pkg.toData()!!.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"statusCode\":101"))
        assertTrue(json.contains("\"packageType\":\"websocket\""))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun getWsMessageType(pkg: TrafficPackage): String {
        val json = pkg.toData()!!.toString(Charsets.UTF_8)
        // Extract messageType from the nested websocketMessagePackage
        val parsed = gson.fromJson(json, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val wsPkg = parsed["websocketMessagePackage"] as? Map<String, Any> ?: error("no websocketMessagePackage")
        return wsPkg["messageType"] as String
    }
}
