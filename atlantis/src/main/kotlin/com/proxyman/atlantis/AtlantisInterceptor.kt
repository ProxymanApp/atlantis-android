package com.proxyman.atlantis

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.GzipSource
import okio.buffer
import java.io.IOException
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.Executors

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
class AtlantisInterceptor internal constructor(
    private val recorder: TrafficRecorder = AtlantisRecorder
) : Interceptor {
    
    companion object {
        private const val TAG = "AtlantisInterceptor"
        private const val MAX_BODY_SIZE = 52428800L // 50MB
        private val UTF8 = Charset.forName("UTF-8")
        private val BODY_TOO_LARGE = "<Body too large>".toByteArray()
        private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Atlantis-Capture").apply { isDaemon = true }
        }
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
            val endAt = System.currentTimeMillis() / 1000.0
            capture {
                val trafficPackage = TrafficPackage(
                    id = requestId,
                    startAt = startTime,
                    request = captureRequestMetadata(originalRequest, capturedRequestBody),
                    endAt = endAt,
                    error = CustomError.fromException(e)
                )
                recorder.sendPackage(trafficPackage)
            }
            
            throw e
        }
        
        // Skip WebSocket upgrade responses (101 Switching Protocols).
        // WebSocket traffic is handled entirely by AtlantisWebSocketListener.
        if (response.code == 101) {
            return response
        }

        // Never read the body here: buffering it would delay the caller until the whole
        // response is downloaded (forever for Server-Sent Events). Instead, copy the bytes
        // as the caller reads them and send the flow from a background thread once the
        // body is exhausted, closed or fails.
        val responseBody = response.body ?: return response
        return try {
            if (hasNoBody(originalRequest, response, responseBody)) {
                sendTrafficPackage(requestId, startTime, originalRequest, capturedRequestBody, response)
                return response
            }
            val capturingBody = CapturingResponseBody(responseBody) { data, truncated, error ->
                sendTrafficPackage(requestId, startTime, originalRequest, capturedRequestBody, response, data, truncated, error)
            }
            response.newBuilder().body(capturingBody).build()
        } catch (captureError: Exception) {
            response
        }
    }

    private fun sendTrafficPackage(
        requestId: String,
        startTime: Double,
        request: Request,
        capturedRequestBody: ByteArray?,
        response: Response,
        data: ByteArray = ByteArray(0),
        truncated: Boolean = false,
        error: IOException? = null
    ) {
        val endAt = System.currentTimeMillis() / 1000.0
        capture {
            val (atlantisResponse, responseBodyData) = captureResponse(response, data, truncated)
            val trafficPackage = TrafficPackage(
                id = requestId,
                startAt = startTime,
                request = captureRequestMetadata(request, capturedRequestBody),
                response = atlantisResponse,
                responseBodyData = responseBodyData,
                endAt = endAt,
                error = error?.let { CustomError.fromException(it) }
            )
            recorder.sendPackage(trafficPackage)
        }
    }

    /**
     * Run capture work off the caller's thread, in order, ignoring any failure
     */
    private fun capture(block: () -> Unit) {
        captureExecutor.execute {
            try {
                block()
            } catch (captureError: Exception) {
                // Silently ignore capture errors - never affect the app
            }
        }
    }

    /**
     * Responses without a body may never be read or closed by the caller, so send them right away
     */
    private fun hasNoBody(request: Request, response: Response, body: ResponseBody): Boolean =
        request.method == "HEAD" || response.code == 204 || response.code == 304 || body.contentLength() == 0L
    
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
        
        // Process captured body (decompress if needed)
        val processedBody = if (capturedBody != null) {
            decompressIfGzip(capturedBody, request.header("Content-Encoding"))
        } else {
            null
        }
        
        return com.proxyman.atlantis.Request.fromOkHttp(
            url = url,
            method = method,
            headers = Atlantis.headersToSingleValueMap(request.headers),
            body = processedBody
        )
    }
    
    /**
     * Decompress a captured gzip body for readability
     */
    private fun decompressIfGzip(data: ByteArray, contentEncoding: String?): ByteArray {
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
     * Capture response details and the body copied while the caller read it
     * Returns a Pair of (Response, Base64EncodedBody)
     */
    private fun captureResponse(
        response: Response,
        bodyData: ByteArray,
        truncated: Boolean
    ): Pair<com.proxyman.atlantis.Response, String> {
        val atlantisResponse = com.proxyman.atlantis.Response.fromOkHttp(
            statusCode = response.code,
            headers = Atlantis.headersToSingleValueMap(response.headers)
        )
        
        val body = if (truncated) BODY_TOO_LARGE else decompressIfGzip(bodyData, response.header("Content-Encoding"))
        val bodyBase64 = if (body.isNotEmpty()) Base64Utils.encode(body) else ""
        
        return Pair(atlantisResponse, bodyBase64)
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

    /**
     * A ResponseBody wrapper that copies the body data as the caller reads it
     * [onComplete] is invoked once, when the stream is exhausted, closed or fails
     */
    internal class CapturingResponseBody(
        private val delegate: ResponseBody,
        private val onComplete: (data: ByteArray, truncated: Boolean, error: IOException?) -> Unit
    ) : ResponseBody() {
        
        private val lock = Any()
        private val captured = Buffer()
        private var truncated = delegate.contentLength() > MAX_BODY_SIZE
        private var error: IOException? = null
        private var completed = false
        
        private val source: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = try {
                        super.read(sink, byteCount)
                    } catch (e: IOException) {
                        synchronized(lock) { error = e }
                        complete()
                        throw e
                    }
                    if (bytesRead == -1L) {
                        complete()
                    } else {
                        record(sink, sink.size - bytesRead, bytesRead)
                    }
                    return bytesRead
                }
                
                override fun close() {
                    complete()
                    super.close()
                }
            }.buffer()
        }
        
        override fun contentType(): MediaType? = delegate.contentType()
        
        override fun contentLength(): Long = delegate.contentLength()
        
        override fun source(): BufferedSource = source
        
        private fun record(sink: Buffer, offset: Long, byteCount: Long) {
            try {
                synchronized(lock) {
                    if (completed) return
                    if (!truncated) {
                        if (captured.size + byteCount > MAX_BODY_SIZE) {
                            truncated = true
                            captured.clear()
                        } else {
                            sink.copyTo(captured, offset, byteCount)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently ignore capture errors - never affect the app
            }
        }
        
        private fun complete() {
            try {
                val (data, isTruncated, failure) = synchronized(lock) {
                    if (completed) return
                    completed = true
                    Triple(captured.readByteArray(), truncated, error)
                }
                onComplete(data, isTruncated, failure)
            } catch (e: Exception) {
                // Silently ignore capture callback errors
            }
        }
    }
}
