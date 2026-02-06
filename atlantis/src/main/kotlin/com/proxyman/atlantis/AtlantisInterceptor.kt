package com.proxyman.atlantis

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.GzipSource
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID

/**
 * OkHttp Interceptor that captures HTTP/HTTPS traffic and sends it to Proxyman
 * 
 * This interceptor is designed to be completely transparent - it will NEVER
 * interfere with normal HTTP requests, even if Proxyman is not running.
 * 
 * This interceptor should be added to your OkHttpClient:
 * ```
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(Atlantis.getInterceptor())
 *     .build()
 * ```
 * 
 * Works automatically with Retrofit, Apollo, and any library that uses OkHttp.
 */
class AtlantisInterceptor internal constructor() : Interceptor {
    
    companion object {
        private const val TAG = "AtlantisInterceptor"
        private const val MAX_BODY_SIZE = 52428800L // 50MB
        private val UTF8 = Charset.forName("UTF-8")
    }
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis() / 1000.0
        
        // Wrap the request body to capture it as it's written (non-destructive)
        var capturedRequestBody: ByteArray? = null
        val requestToSend = if (originalRequest.body != null && canCaptureRequestBody(originalRequest.body!!)) {
            val wrappedBody = CapturingRequestBody(originalRequest.body!!) { data ->
                capturedRequestBody = data
            }
            originalRequest.newBuilder().method(originalRequest.method, wrappedBody).build()
        } else {
            originalRequest
        }
        
        // Execute the request FIRST - this is the priority
        // Atlantis should NEVER block or fail the actual HTTP request
        val response: Response
        
        try {
            response = chain.proceed(requestToSend)
        } catch (e: IOException) {
            // Request failed, but we still want to log it
            // Create and send error package (best effort, ignore capture failures)
            try {
                val trafficPackage = TrafficPackage(
                    id = requestId,
                    startAt = startTime,
                    request = captureRequestMetadata(originalRequest, capturedRequestBody),
                    endAt = System.currentTimeMillis() / 1000.0,
                    error = CustomError.fromException(e)
                )
                Atlantis.sendPackage(trafficPackage)
            } catch (captureError: Exception) {
                // Silently ignore capture errors - never affect the app
            }
            
            throw e
        }
        
        // Skip WebSocket upgrade responses (101 Switching Protocols).
        // WebSocket traffic is handled entirely by AtlantisWebSocketListener.
        if (response.code == 101) {
            return response
        }

        // Request succeeded, now capture the response (best effort)
        try {
            val (atlantisResponse, responseBodyData) = captureResponse(response)
            val trafficPackage = TrafficPackage(
                id = requestId,
                startAt = startTime,
                request = captureRequestMetadata(originalRequest, capturedRequestBody),
                response = atlantisResponse,
                responseBodyData = responseBodyData,
                endAt = System.currentTimeMillis() / 1000.0
            )
            Atlantis.sendPackage(trafficPackage)
        } catch (captureError: Exception) {
            // Silently ignore capture errors - never affect the app
        }
        
        return response
    }
    
    /**
     * Check if we can safely capture the request body
     * Some body types can only be written once (one-shot) or are streaming (duplex)
     */
    private fun canCaptureRequestBody(body: RequestBody): Boolean {
        // Skip one-shot bodies - they can only be written once
        if (body.isOneShot()) {
            return false
        }
        
        // Skip duplex bodies - they're for bidirectional streaming
        if (body.isDuplex()) {
            return false
        }
        
        // Skip very large bodies
        val contentLength = body.contentLength()
        if (contentLength > MAX_BODY_SIZE) {
            return false
        }
        
        return true
    }
    
    /**
     * Capture request metadata (URL, method, headers) and optionally the body
     */
    private fun captureRequestMetadata(request: Request, capturedBody: ByteArray?): com.proxyman.atlantis.Request {
        val url = request.url.toString()
        val method = request.method
        
        // Capture headers
        val headers = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            val value = request.headers.value(i)
            headers[name] = value
        }
        
        // Process captured body (decompress if needed)
        val processedBody = if (capturedBody != null) {
            processRequestBody(capturedBody, request.header("Content-Encoding"))
        } else {
            null
        }
        
        return com.proxyman.atlantis.Request.fromOkHttp(
            url = url,
            method = method,
            headers = headers,
            body = processedBody
        )
    }
    
    /**
     * Process captured request body (e.g., decompress gzip)
     */
    private fun processRequestBody(data: ByteArray, contentEncoding: String?): ByteArray {
        if (contentEncoding.equals("gzip", ignoreCase = true)) {
            return try {
                val buffer = Buffer().write(data)
                val gzipSource = GzipSource(buffer)
                val decompressedBuffer = Buffer()
                decompressedBuffer.writeAll(gzipSource)
                decompressedBuffer.readByteArray()
            } catch (e: Exception) {
                data // Return original if decompression fails
            }
        }
        return data
    }
    
    /**
     * Capture response details and body
     * Returns a Pair of (Response, Base64EncodedBody)
     */
    private fun captureResponse(response: Response): Pair<com.proxyman.atlantis.Response, String> {
        val statusCode = response.code
        
        // Capture headers
        val headers = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            val name = response.headers.name(i)
            val value = response.headers.value(i)
            headers[name] = value
        }
        
        val atlantisResponse = com.proxyman.atlantis.Response.fromOkHttp(
            statusCode = statusCode,
            headers = headers
        )
        
        // Capture body (best effort)
        val bodyData = captureResponseBody(response)
        val bodyBase64 = if (bodyData != null && bodyData.isNotEmpty()) {
            Base64Utils.encode(bodyData)
        } else {
            ""
        }
        
        return Pair(atlantisResponse, bodyBase64)
    }
    
    /**
     * Capture response body without consuming the original response
     * Uses OkHttp's peekBody-like approach to safely read without affecting the caller
     */
    private fun captureResponseBody(response: Response): ByteArray? {
        val responseBody = response.body ?: return null
        
        // Skip if body is too large
        val contentLength = responseBody.contentLength()
        if (contentLength > MAX_BODY_SIZE) {
            return "<Body too large>".toByteArray()
        }
        
        return try {
            // Peek the body without consuming it
            // This is safe because OkHttp buffers the response for us
            val source = responseBody.source()
            source.request(Long.MAX_VALUE) // Buffer the entire body
            var buffer = source.buffer.clone()
            
            // Check if response is gzip compressed
            val contentEncoding = response.header("Content-Encoding")
            if (contentEncoding.equals("gzip", ignoreCase = true)) {
                // Decompress for readability
                val gzipSource = GzipSource(buffer)
                val decompressedBuffer = Buffer()
                decompressedBuffer.writeAll(gzipSource)
                buffer = decompressedBuffer
            }
            
            // Limit body size for safety
            val size = minOf(buffer.size, MAX_BODY_SIZE)
            buffer.readByteArray(size)
        } catch (e: Exception) {
            // Return null on any error - don't break the response
            null
        }
    }
    
    /**
     * A RequestBody wrapper that captures the body data as it's being written
     * This is non-destructive - the original body is written to the network normally
     */
    private class CapturingRequestBody(
        private val delegate: RequestBody,
        private val onCapture: (ByteArray) -> Unit
    ) : RequestBody() {
        
        override fun contentType(): MediaType? = delegate.contentType()
        
        override fun contentLength(): Long = delegate.contentLength()
        
        override fun isOneShot(): Boolean = delegate.isOneShot()
        
        override fun isDuplex(): Boolean = delegate.isDuplex()
        
        override fun writeTo(sink: BufferedSink) {
            // Create a buffer to capture the data
            val captureBuffer = Buffer()
            
            // Write to the capture buffer first
            delegate.writeTo(captureBuffer)
            
            // Capture the data
            val capturedData = captureBuffer.clone().readByteArray()
            try {
                onCapture(capturedData)
            } catch (e: Exception) {
                // Silently ignore capture callback errors
            }
            
            // Write the captured data to the actual sink
            sink.writeAll(captureBuffer)
        }
    }
}
