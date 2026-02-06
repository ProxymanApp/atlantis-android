package com.proxyman.atlantis

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MessageTest {
    
    private val gson = Gson()

    private fun extractContent(json: String): String? {
        val map = gson.fromJson(json, Map::class.java)
        return map["content"] as? String
    }
    
    @Test
    fun `test MessageType serialization`() {
        assertEquals("\"connection\"", gson.toJson(Message.MessageType.CONNECTION))
        assertEquals("\"traffic\"", gson.toJson(Message.MessageType.TRAFFIC))
        assertEquals("\"websocket\"", gson.toJson(Message.MessageType.WEBSOCKET))
    }
    
    @Test
    fun `test build connection message`() {
        val testPackage = TestSerializable("test content")
        val message = Message.buildConnectionMessage("test-id", testPackage)
        
        val json = message.toData()?.toString(Charsets.UTF_8)
        assertNotNull(json)
        
        assertTrue(json!!.contains("\"messageType\":\"connection\""))
        assertTrue(json.contains("\"id\":\"test-id\""))
        assertTrue(json.contains("\"buildVersion\""))

        val content = extractContent(json)
        assertNotNull(content)
        val decoded = Base64Utils.decode(content!!).toString(Charsets.UTF_8)
        val expectedPayload = gson.toJson(testPackage)
        assertEquals(expectedPayload, decoded)
    }
    
    @Test
    fun `test build traffic message`() {
        val testPackage = TestSerializable("test traffic")
        val message = Message.buildTrafficMessage("traffic-id", testPackage)
        
        val json = message.toData()?.toString(Charsets.UTF_8)
        assertNotNull(json)
        
        assertTrue(json!!.contains("\"messageType\":\"traffic\""))
        assertTrue(json.contains("\"id\":\"traffic-id\""))

        val content = extractContent(json)
        assertNotNull(content)
        val decoded = Base64Utils.decode(content!!).toString(Charsets.UTF_8)
        val expectedPayload = gson.toJson(testPackage)
        assertEquals(expectedPayload, decoded)
    }
    
    @Test
    fun `test build websocket message`() {
        val testPackage = TestSerializable("ws message")
        val message = Message.buildWebSocketMessage("ws-id", testPackage)
        
        val json = message.toData()?.toString(Charsets.UTF_8)
        assertNotNull(json)
        
        assertTrue(json!!.contains("\"messageType\":\"websocket\""))
        assertTrue(json.contains("\"id\":\"ws-id\""))

        val content = extractContent(json)
        assertNotNull(content)
        val decoded = Base64Utils.decode(content!!).toString(Charsets.UTF_8)
        val expectedPayload = gson.toJson(testPackage)
        assertEquals(expectedPayload, decoded)
    }

    @Test
    fun `test build websocket message with TrafficPackage payload`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )

        val trafficPackage = TrafficPackage.createWebSocket(request).apply {
            response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))
            websocketMessagePackage = WebsocketMessagePackage.createStringMessage(
                id = id,
                message = "hello",
                type = WebsocketMessagePackage.MessageType.RECEIVE
            )
        }

        val message = Message.buildWebSocketMessage("config-id", trafficPackage)
        val json = message.toData()!!.toString(Charsets.UTF_8)
        val content = extractContent(json)!!
        val decoded = Base64Utils.decode(content).toString(Charsets.UTF_8)

        assertTrue(json.contains("\"messageType\":\"websocket\""))
        assertTrue(decoded.contains("\"packageType\":\"websocket\""))
        assertTrue(decoded.contains("\"websocketMessagePackage\""))
        assertTrue(decoded.contains("\"messageType\":\"receive\""))
        assertTrue(decoded.contains("\"stringValue\":\"hello\""))
    }
    
    // Helper test class
    private class TestSerializable(val content: String) : Serializable {
        override fun toData(): ByteArray? {
            return Gson().toJson(this).toByteArray(Charsets.UTF_8)
        }
    }
}
