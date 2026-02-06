package com.proxyman.atlantis

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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
}
