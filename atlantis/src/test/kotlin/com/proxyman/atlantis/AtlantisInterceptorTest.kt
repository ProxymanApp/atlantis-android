package com.proxyman.atlantis

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AtlantisInterceptorTest {
    
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var interceptor: AtlantisInterceptor
    
    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        
        interceptor = AtlantisInterceptor()
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
    
    @After
    fun teardown() {
        mockWebServer.shutdown()
    }
    
    @Test
    fun `test interceptor captures GET request`() {
        // Enqueue a mock response
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("{\"message\":\"success\"}")
            .addHeader("Content-Type", "application/json"))
        
        // Make request
        val request = Request.Builder()
            .url(mockWebServer.url("/api/test"))
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        
        // Verify response was not affected
        assertEquals(200, response.code)
        assertNotNull(response.body)
    }
    
    @Test
    fun `test interceptor captures POST request with body`() {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(201)
            .setBody("{\"id\":123}")
            .addHeader("Content-Type", "application/json"))
        
        val requestBody = "{\"name\":\"test\"}".toRequestBody()
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/users"))
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        
        assertEquals(201, response.code)
    }
    
    @Test
    fun `test interceptor handles error response`() {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(404)
            .setBody("{\"error\":\"Not found\"}"))
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/notfound"))
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        
        assertEquals(404, response.code)
    }
    
    @Test
    fun `test interceptor handles empty response body`() {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(204))
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/delete"))
            .delete()
            .build()
        
        val response = client.newCall(request).execute()
        
        assertEquals(204, response.code)
    }
    
    @Test
    fun `test interceptor preserves response body for consumer`() {
        val expectedBody = "{\"data\":\"test content\"}"
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(expectedBody)
            .addHeader("Content-Type", "application/json"))
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/data"))
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        val actualBody = response.body?.string()
        
        // The interceptor should not consume the body
        assertEquals(expectedBody, actualBody)
    }
    
    @Test
    fun `test interceptor captures headers`() {
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("OK")
            .addHeader("X-Custom-Header", "custom-value")
            .addHeader("X-Request-Id", "12345"))
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/headers"))
            .get()
            .addHeader("Authorization", "Bearer token123")
            .addHeader("Accept", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        
        assertEquals(200, response.code)
        assertEquals("custom-value", response.header("X-Custom-Header"))
    }
    
    @Test
    fun `test interceptor handles large response`() {
        // Create a large response body
        val largeBody = "X".repeat(100000)
        
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(largeBody))
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/large"))
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        
        assertEquals(200, response.code)
        assertEquals(largeBody.length, body?.length)
    }
    
    @Test
    fun `test interceptor handles redirect`() {
        // First response: redirect
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(302)
            .addHeader("Location", mockWebServer.url("/api/final").toString()))
        
        // Second response: final destination
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("{\"redirected\":true}"))
        
        val request = Request.Builder()
            .url(mockWebServer.url("/api/redirect"))
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        
        assertEquals(200, response.code)
    }
    
    @Test
    fun `test interceptor skips WebSocket upgrade 101 response`() {
        // Return a 101 Switching Protocols response (WebSocket upgrade)
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(101)
            .addHeader("Upgrade", "websocket")
            .addHeader("Connection", "Upgrade"))

        val request = Request.Builder()
            .url(mockWebServer.url("/ws"))
            .get()
            .addHeader("Connection", "Upgrade")
            .addHeader("Upgrade", "websocket")
            .build()

        val response = client.newCall(request).execute()

        // Verify the interceptor does not interfere with the 101 response
        assertEquals(101, response.code)
        assertEquals("websocket", response.header("Upgrade"))
    }

    @Test
    fun `test interceptor still captures non-101 responses`() {
        // A normal 200 response must still be captured (not skipped)
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("{\"ok\":true}"))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/health"))
            .get()
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("{\"ok\":true}", response.body?.string())
    }

    @Test
    fun `test multiple concurrent requests`() {
        // Enqueue multiple responses
        repeat(5) { i ->
            mockWebServer.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("{\"index\":$i}"))
        }
        
        // Make concurrent requests
        val threads = (0 until 5).map { i ->
            Thread {
                val request = Request.Builder()
                    .url(mockWebServer.url("/api/concurrent/$i"))
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                assertEquals(200, response.code)
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun `test interceptor does not buffer server-sent events stream`() {
        val event = "data: tick\n\n"
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(event.repeat(3))
            .throttleBody(event.length.toLong(), 1, TimeUnit.SECONDS))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/events"))
            .get()
            .build()

        val start = System.nanoTime()
        val response = client.newCall(request).execute()
        val firstLine = response.body!!.source().readUtf8Line()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertEquals("data: tick", firstLine)
        assertTrue("First event took ${elapsedMs}ms, stream was buffered", elapsedMs < 1000)
        response.close()
    }

    @Test
    fun `test interceptor returns response before body is downloaded`() {
        val chunk = "{\"a\":1}"
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody(chunk.repeat(3))
            .throttleBody(chunk.length.toLong(), 1, TimeUnit.SECONDS))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/slow"))
            .get()
            .build()

        val start = System.nanoTime()
        val response = client.newCall(request).execute()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertTrue("Response took ${elapsedMs}ms, body was buffered", elapsedMs < 1000)
        assertEquals(chunk.repeat(3), response.body!!.string())
    }

    @Test
    fun `test interceptor preserves server-sent events body for consumer`() {
        val expectedBody = "data: one\n\ndata: two\n\n"
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setBody(expectedBody))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/events"))
            .get()
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(expectedBody, response.body?.string())
    }

    @Test
    fun `test interceptor does not block websocket messages`() {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("first")
            }
        }))

        val firstMessage = CountDownLatch(1)
        val request = Request.Builder()
            .url(mockWebServer.url("/ws"))
            .build()

        val webSocket = client.newWebSocket(request, Atlantis.wrapWebSocketListener(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "first") firstMessage.countDown()
            }
        }))

        assertTrue("First message was not delivered while the socket is open", firstMessage.await(1, TimeUnit.SECONDS))
        webSocket.close(1000, null)
    }

    @Test
    fun `test capturing body marks bodies larger than the limit as truncated`() {
        var truncated: Boolean? = null
        var captured: ByteArray? = null
        val body = AtlantisInterceptor.CapturingResponseBody(
            delegate = object : okhttp3.ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = 100L * 1024 * 1024
                override fun source() = okio.Buffer().writeUtf8("data")
            },
            onComplete = { data, isTruncated, _ ->
                captured = data
                truncated = isTruncated
            }
        )

        body.source().readUtf8()

        assertEquals(true, truncated)
        assertEquals(0, captured!!.size)
    }

    @Test
    fun `test recorder receives response once body is read`() {
        val recorder = FakeRecorder()
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "a=1")
            .addHeader("Set-Cookie", "b=2")
            .setBody("{\"message\":\"success\"}"))

        val response = clientWith(recorder).newCall(Request.Builder().url(mockWebServer.url("/api/test")).build()).execute()
        assertEquals("{\"message\":\"success\"}", response.body!!.string())

        val trafficPackage = recorder.nextPackage()
        assertEquals(200, trafficPackage.response!!.statusCode)
        assertEquals("{\"message\":\"success\"}", decode(trafficPackage.responseBodyData))
        assertEquals("a=1,b=2", trafficPackage.response!!.headers.single { it.key == "Set-Cookie" }.value)
        assertNull(trafficPackage.error)
    }

    @Test
    fun `test recorder receives response without body even if it is never closed`() {
        val recorder = FakeRecorder()
        mockWebServer.enqueue(MockResponse().setResponseCode(204))

        clientWith(recorder).newCall(Request.Builder().url(mockWebServer.url("/api/empty")).build()).execute()

        assertEquals(204, recorder.nextPackage().response!!.statusCode)
    }

    @Test
    fun `test recorder receives failed response body as an error`() {
        val recorder = FakeRecorder()
        mockWebServer.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("{\"a\":1}".repeat(1000))
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

        val response = clientWith(recorder).newCall(Request.Builder().url(mockWebServer.url("/api/test")).build()).execute()
        assertThrows(java.io.IOException::class.java) { response.body!!.source().readUtf8() }

        assertNotNull(recorder.nextPackage().error)
    }

    private fun clientWith(recorder: TrafficRecorder) = OkHttpClient.Builder()
        .addInterceptor(AtlantisInterceptor(recorder))
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun decode(base64: String) = String(java.util.Base64.getDecoder().decode(base64))

    private class FakeRecorder : TrafficRecorder {
        private val packages = LinkedBlockingQueue<TrafficPackage>()

        fun nextPackage(): TrafficPackage = packages.poll(5, TimeUnit.SECONDS) ?: error("No package recorded")

        override fun sendPackage(trafficPackage: TrafficPackage) {
            packages.add(trafficPackage)
        }
    }
}
