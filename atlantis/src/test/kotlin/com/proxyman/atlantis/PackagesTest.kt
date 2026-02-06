package com.proxyman.atlantis

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PackagesTest {
    
    private val gson = Gson()
    
    @Test
    fun `test Header creation`() {
        val header = Header("Content-Type", "application/json")
        
        assertEquals("Content-Type", header.key)
        assertEquals("application/json", header.value)
    }
    
    @Test
    fun `test Header serialization`() {
        val header = Header("X-Custom", "test-value")
        val json = gson.toJson(header)
        
        assertTrue(json.contains("\"key\":\"X-Custom\""))
        assertTrue(json.contains("\"value\":\"test-value\""))
    }
    
    @Test
    fun `test Request creation from OkHttp`() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer token"
        )
        val body = "{\"name\":\"test\"}".toByteArray()
        
        val request = Request.fromOkHttp(
            url = "https://api.example.com/users",
            method = "POST",
            headers = headers,
            body = body
        )
        
        assertEquals("https://api.example.com/users", request.url)
        assertEquals("POST", request.method)
        assertEquals(2, request.headers.size)
        assertNotNull(request.body)
    }
    
    @Test
    fun `test Request body is Base64 encoded`() {
        val body = "Hello World".toByteArray()
        val request = Request.fromOkHttp(
            url = "https://example.com",
            method = "POST",
            headers = emptyMap(),
            body = body
        )
        
        // Body should be Base64 encoded
        val expectedBase64 = Base64Utils.encode(body)
        assertEquals(expectedBase64, request.body)
    }
    
    @Test
    fun `test Request with null body`() {
        val request = Request.fromOkHttp(
            url = "https://example.com",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        
        assertNull(request.body)
    }
    
    @Test
    fun `test Response creation from OkHttp`() {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Content-Length" to "1234"
        )
        
        val response = Response.fromOkHttp(
            statusCode = 200,
            headers = headers
        )
        
        assertEquals(200, response.statusCode)
        assertEquals(2, response.headers.size)
    }
    
    @Test
    fun `test Response serialization`() {
        val response = Response.fromOkHttp(
            statusCode = 404,
            headers = mapOf("X-Error" to "Not Found")
        )
        
        val json = gson.toJson(response)
        assertTrue(json.contains("\"statusCode\":404"))
        assertTrue(json.contains("\"key\":\"X-Error\""))
    }
    
    @Test
    fun `test CustomError from Exception`() {
        val exception = RuntimeException("Network error")
        val error = CustomError.fromException(exception)
        
        assertEquals(-1, error.code)
        assertEquals("Network error", error.message)
    }
    
    @Test
    fun `test TrafficPackage creation`() {
        val request = Request.fromOkHttp(
            url = "https://api.example.com/data",
            method = "GET",
            headers = emptyMap(),
            body = null
        )
        
        val trafficPackage = TrafficPackage.create(request)
        
        assertNotNull(trafficPackage.id)
        assertTrue(trafficPackage.startAt > 0)
        assertEquals(request, trafficPackage.request)
        assertNull(trafficPackage.response)
        assertNull(trafficPackage.error)
        assertEquals(TrafficPackage.PackageType.HTTP, trafficPackage.packageType)
    }

    @Test
    fun `test TrafficPackage WebSocket creation`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = mapOf("Sec-WebSocket-Protocol" to "chat"),
            body = null
        )

        val trafficPackage = TrafficPackage.createWebSocket(request)

        assertNotNull(trafficPackage.id)
        assertTrue(trafficPackage.startAt > 0)
        assertEquals(request, trafficPackage.request)
        assertEquals(TrafficPackage.PackageType.WEBSOCKET, trafficPackage.packageType)
        assertNull(trafficPackage.websocketMessagePackage)
    }

    @Test
    fun `test TrafficPackage WebSocket serialization with websocketMessagePackage`() {
        val request = Request.fromOkHttp(
            url = "wss://echo.websocket.org/",
            method = "GET",
            headers = emptyMap(),
            body = null
        )

        val trafficPackage = TrafficPackage.createWebSocket(request)
        trafficPackage.response = Response.fromOkHttp(101, mapOf("Upgrade" to "websocket"))
        trafficPackage.websocketMessagePackage =
            WebsocketMessagePackage.createStringMessage(
                id = trafficPackage.id,
                message = "hello",
                type = WebsocketMessagePackage.MessageType.SEND
            )

        val json = trafficPackage.toData()!!.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"packageType\":\"websocket\""))
        assertTrue(json.contains("\"websocketMessagePackage\""))
        assertTrue(json.contains("\"messageType\":\"send\""))
        assertTrue(json.contains("\"stringValue\":\"hello\""))
    }
    
    @Test
    fun `test TrafficPackage serialization`() {
        val request = Request.fromOkHttp(
            url = "https://api.example.com",
            method = "GET",
            headers = mapOf("Accept" to "application/json"),
            body = null
        )
        
        val trafficPackage = TrafficPackage.create(request)
        trafficPackage.response = Response.fromOkHttp(200, mapOf("Content-Type" to "application/json"))
        trafficPackage.endAt = System.currentTimeMillis() / 1000.0
        
        val data = trafficPackage.toData()
        assertNotNull(data)
        
        val json = data!!.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"url\":\"https://api.example.com\""))
        assertTrue(json.contains("\"method\":\"GET\""))
        assertTrue(json.contains("\"statusCode\":200"))
        assertTrue(json.contains("\"packageType\":\"http\""))
    }
    
    @Test
    fun `test Device current`() {
        val device = Device.current()
        
        assertNotNull(device.name)
        assertNotNull(device.model)
        // In JUnit tests, Build.MODEL is null so it falls back to "Unknown Device"
        // and model will contain "Unknown Unknown (Android Unknown)"
        assertTrue(device.name.isNotEmpty())
        assertTrue(device.model.isNotEmpty())
    }
    
    @Test
    fun `test Device with custom name`() {
        val device = Device.current("My Test Device")
        
        assertEquals("My Test Device", device.name)
    }
    
    @Test
    fun `test Project current`() {
        val project = Project.current(null, "com.example.app")
        
        assertEquals("com.example.app", project.name)
        assertEquals("com.example.app", project.bundleIdentifier)
    }
    
    @Test
    fun `test Project with custom name`() {
        val project = Project.current("My App", "com.example.app")
        
        assertEquals("My App", project.name)
        assertEquals("com.example.app", project.bundleIdentifier)
    }
    
    @Test
    fun `test WebsocketMessagePackage string message`() {
        val wsPackage = WebsocketMessagePackage.createStringMessage(
            id = "ws-123",
            message = "Hello WebSocket",
            type = WebsocketMessagePackage.MessageType.SEND
        )
        
        val data = wsPackage.toData()
        assertNotNull(data)
        
        val json = data!!.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"id\":\"ws-123\""))
        assertTrue(json.contains("\"messageType\":\"send\""))
        assertTrue(json.contains("\"stringValue\":\"Hello WebSocket\""))
    }
    
    @Test
    fun `test WebsocketMessagePackage data message`() {
        val payload = "Binary data".toByteArray()
        val wsPackage = WebsocketMessagePackage.createDataMessage(
            id = "ws-456",
            data = payload,
            type = WebsocketMessagePackage.MessageType.RECEIVE
        )
        
        val data = wsPackage.toData()
        assertNotNull(data)
        
        val json = data!!.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"id\":\"ws-456\""))
        assertTrue(json.contains("\"messageType\":\"receive\""))
        assertTrue(json.contains("\"dataValue\""))
    }
    
    @Test
    fun `test WebsocketMessagePackage close message`() {
        val wsPackage = WebsocketMessagePackage.createCloseMessage(
            id = "ws-close",
            closeCode = 1000,
            reason = "Normal closure"
        )
        
        val data = wsPackage.toData()
        assertNotNull(data)
        
        val json = data!!.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"messageType\":\"sendCloseMessage\""))
        assertTrue(json.contains("\"stringValue\":\"1000\""))
    }
}
