package com.proxyman.atlantis.sample

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.proxyman.atlantis.Atlantis
import com.proxyman.atlantis.Transporter
import com.proxyman.atlantis.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Main Activity demonstrating Atlantis with OkHttp and Retrofit.
 * Showcases basic HTTP methods, advanced request types (form data,
 * multipart, complex queries, custom headers), and various response types.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AtlantisSample"
        private const val BASE_URL = "https://httpbin.org"
    }

    private lateinit var binding: ActivityMainBinding
    private var connectionState: String? = null
    private var httpLog: String = ""
    private var wsLog: String = ""

    private val connectionListener = object : Transporter.ConnectionListener {
        override fun onConnected(host: String, port: Int) {
            connectionState = "Connected to Proxyman at $host:$port"
            runOnUiThread { updateStatus() }
        }

        override fun onDisconnected() {
            connectionState = "Disconnected. Looking for Proxyman..."
            runOnUiThread { updateStatus() }
        }

        override fun onConnectionFailed(error: String) {
            connectionState = "Connection failed: $error"
            runOnUiThread { updateStatus() }
        }
    }

    // OkHttpClient shared from Application (also used by WebSocket test)
    private val okHttpClient: OkHttpClient by lazy {
        (application as SampleApplication).okHttpClient
    }

    // Retrofit instance using the OkHttpClient
    private val retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://httpbin.proxyman.app/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val httpBinApi by lazy {
        retrofit.create(HttpBinApi::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Atlantis.setConnectionListener(connectionListener)
        setupUI()
        observeWebSocketLogs()
    }

    override fun onDestroy() {
        Atlantis.setConnectionListener(null)
        super.onDestroy()
    }

    // ── UI Setup ────────────────────────────────────────────────────────

    private fun setupUI() {
        // Basic requests
        binding.btnGetRequest.setOnClickListener { makeGetRequest() }
        binding.btnPostRequest.setOnClickListener { makePostRequest() }
        binding.btnPutRequest.setOnClickListener { makePutRequest() }
        binding.btnPatchRequest.setOnClickListener { makePatchRequest() }
        binding.btnDeleteRequest.setOnClickListener { makeDeleteRequest() }

        // Advanced requests
        binding.btnComplexQuery.setOnClickListener { makeComplexQueryRequest() }
        binding.btnFormData.setOnClickListener { makeFormDataRequest() }
        binding.btnMultipart.setOnClickListener { makeMultipartRequest() }
        binding.btnCustomHeaders.setOnClickListener { makeCustomHeadersRequest() }
        binding.btnRetrofitRequest.setOnClickListener { makeRetrofitRequest() }

        // Response types
        binding.btnJsonRequest.setOnClickListener { makeJsonRequest() }
        binding.btnBigJson.setOnClickListener { makeBigJsonRequest() }
        binding.btnImageDownload.setOnClickListener { makeImageDownloadRequest() }
        binding.btnErrorRequest.setOnClickListener { makeErrorRequest() }
        binding.btnDelayedResponse.setOnClickListener { makeDelayedRequest() }

        // WebSocket
        binding.btnStartWebSocketTest.setOnClickListener {
            WebSocketTestController.startAutoTest(okHttpClient)
        }

        updateStatus()
        updateLogView()
    }

    private fun updateStatus() {
        val status: String
        val dotColor: Int

        if (!Atlantis.isRunning()) {
            status = "Atlantis is not running"
            dotColor = ContextCompat.getColor(this, R.color.status_disconnected)
        } else {
            val detail = connectionState ?: "Looking for Proxyman..."
            status = detail
            dotColor = when {
                connectionState?.startsWith("Connected") == true ->
                    ContextCompat.getColor(this, R.color.status_connected)
                connectionState?.startsWith("Connection failed") == true ->
                    ContextCompat.getColor(this, R.color.status_disconnected)
                else ->
                    ContextCompat.getColor(this, R.color.status_searching)
            }
        }

        binding.tvStatus.text = status
        val dot = binding.statusDot.background
        if (dot is GradientDrawable) {
            dot.setColor(dotColor)
        }
    }

    private fun observeWebSocketLogs() {
        lifecycleScope.launch {
            WebSocketTestController.logText.collect { text ->
                wsLog = text
                updateLogView()
            }
        }

        lifecycleScope.launch {
            WebSocketTestController.isTestRunning.collect { running ->
                binding.btnStartWebSocketTest.isEnabled = !running
            }
        }
    }

    private fun updateLogView() {
        val combined = buildString {
            if (httpLog.isNotBlank()) {
                append("── HTTP ──\n")
                append(httpLog)
                append("\n\n")
            }
            append("── WebSocket ──\n")
            append(if (wsLog.isNotBlank()) wsLog else "(no websocket logs yet)")
        }
        binding.tvResult.text = combined
    }

    // ── Basic Requests ──────────────────────────────────────────────────

    private fun makeGetRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/get")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("GET Request", result)
            } catch (e: Exception) {
                showError("GET Request failed", e)
            }
        }
    }

    private fun makePostRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val jsonBody = """{"name": "Atlantis", "platform": "Android", "version": "1.0"}"""
                    val body = jsonBody.toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url("$BASE_URL/post")
                        .post(body)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("POST Request", result)
            } catch (e: Exception) {
                showError("POST Request failed", e)
            }
        }
    }

    private fun makePutRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val jsonBody = """
                        {
                            "id": 42,
                            "name": "Atlantis",
                            "platform": "Android",
                            "enabled": true,
                            "config": {
                                "logLevel": "verbose",
                                "maxRetries": 3
                            }
                        }
                    """.trimIndent()
                    val body = jsonBody.toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url("$BASE_URL/put")
                        .put(body)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("PUT Request", result)
            } catch (e: Exception) {
                showError("PUT Request failed", e)
            }
        }
    }

    private fun makePatchRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val jsonBody = """{"name": "Atlantis Pro", "enabled": false}"""
                    val body = jsonBody.toRequestBody("application/json".toMediaType())

                    val request = Request.Builder()
                        .url("$BASE_URL/patch")
                        .patch(body)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("PATCH Request", result)
            } catch (e: Exception) {
                showError("PATCH Request failed", e)
            }
        }
    }

    private fun makeDeleteRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/delete")
                        .delete()
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("DELETE Request", result)
            } catch (e: Exception) {
                showError("DELETE Request failed", e)
            }
        }
    }

    // ── Advanced Requests ───────────────────────────────────────────────

    private fun makeComplexQueryRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val url = okhttp3.HttpUrl.Builder()
                        .scheme("https")
                        .host("httpbin.org")
                        .addPathSegment("get")
                        .addQueryParameter("search", "atlantis proxy debugger")
                        .addQueryParameter("page", "1")
                        .addQueryParameter("limit", "50")
                        .addQueryParameter("sort", "date")
                        .addQueryParameter("order", "desc")
                        .addQueryParameter("filters[status]", "active")
                        .addQueryParameter("filters[type]", "network")
                        .addQueryParameter("filters[priority]", "high")
                        .addQueryParameter("tags", "debug,proxy,http,ssl")
                        .addQueryParameter("include_metadata", "true")
                        .addQueryParameter("format", "json")
                        .build()

                    val request = Request.Builder()
                        .url(url)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("Complex Query", result)
            } catch (e: Exception) {
                showError("Complex Query failed", e)
            }
        }
    }

    private fun makeFormDataRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val formBody = FormBody.Builder()
                        .add("username", "proxyman_user")
                        .add("password", "s3cur3P@ssw0rd!")
                        .add("email", "user@proxyman.io")
                        .add("first_name", "John")
                        .add("last_name", "Doe")
                        .add("country", "US")
                        .add("language", "en")
                        .add("newsletter", "true")
                        .add("terms_accepted", "true")
                        .build()

                    val request = Request.Builder()
                        .url("$BASE_URL/post")
                        .post(formBody)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("Form Data POST", result)
            } catch (e: Exception) {
                showError("Form Data POST failed", e)
            }
        }
    }

    private fun makeMultipartRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Simulate a file upload with generated bytes
                    val fakeFileContent = buildString {
                        repeat(200) { i ->
                            appendLine("Line $i: Sample log entry for Atlantis network debugger testing")
                        }
                    }
                    val fileBytes = fakeFileContent.toByteArray()

                    val multipartBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("title", "Debug Session Log")
                        .addFormDataPart("description", "Captured network traffic from Atlantis sample app")
                        .addFormDataPart("session_id", "sess_abc123def456")
                        .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                        .addFormDataPart(
                            "file",
                            "debug_log.txt",
                            fileBytes.toRequestBody("text/plain".toMediaType())
                        )
                        .addFormDataPart(
                            "metadata",
                            "metadata.json",
                            """{"app":"atlantis-sample","version":"1.0"}""".toRequestBody("application/json".toMediaType())
                        )
                        .build()

                    val request = Request.Builder()
                        .url("$BASE_URL/post")
                        .post(multipartBody)
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("Multipart Upload", result)
            } catch (e: Exception) {
                showError("Multipart Upload failed", e)
            }
        }
    }

    private fun makeCustomHeadersRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/headers")
                        .addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sample-token")
                        .addHeader("X-Request-ID", "req-${System.currentTimeMillis()}")
                        .addHeader("X-Client-Version", "1.0.0")
                        .addHeader("X-Platform", "Android")
                        .addHeader("X-Device-Model", android.os.Build.MODEL)
                        .addHeader("Accept-Language", "en-US,en;q=0.9,vi;q=0.8")
                        .addHeader("X-Correlation-ID", java.util.UUID.randomUUID().toString())
                        .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        .addHeader("X-Forwarded-For", "192.168.1.100")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("Custom Headers", result)
            } catch (e: Exception) {
                showError("Custom Headers failed", e)
            }
        }
    }

    private fun makeRetrofitRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpBinApi.getIp()
                }
                showResult("Retrofit Request", "Origin IP: ${result.origin}")
            } catch (e: Exception) {
                showError("Retrofit Request failed", e)
            }
        }
    }

    // ── Response Types ──────────────────────────────────────────────────

    private fun makeJsonRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpBinApi.getJson()
                }
                showResult("JSON Response", "Slideshow title: ${result.slideshow?.title}")
            } catch (e: Exception) {
                showError("JSON Request failed", e)
            }
        }
    }

    private fun makeBigJsonRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // httpbin /stream/N returns N JSON lines
                    val request = Request.Builder()
                        .url("$BASE_URL/stream/50")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: "Empty response"
                        "Received ${body.length} bytes, ${body.lines().size} JSON lines"
                    }
                }
                showResult("Big JSON (50 entries)", result)
            } catch (e: Exception) {
                showError("Big JSON failed", e)
            }
        }
    }

    private fun makeImageDownloadRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/image/png")
                        .addHeader("Accept", "image/png")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            "Downloaded PNG image: ${bytes.size} bytes (${response.header("Content-Type")})"
                        } else {
                            "Empty response"
                        }
                    }
                }
                showResult("Image Download", result)
            } catch (e: Exception) {
                showError("Image Download failed", e)
            }
        }
    }

    private fun makeErrorRequest() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/status/404")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("HTTP ${response.code}: ${response.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                showError("Error 404 (expected)", e)
            }
        }
    }

    private fun makeDelayedRequest() {
        lifecycleScope.launch {
            try {
                showResult("Delayed Response", "Waiting 3 seconds...")
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$BASE_URL/delay/3")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        response.body?.string() ?: "Empty response"
                    }
                }
                showResult("Delayed Response", result)
            } catch (e: Exception) {
                showError("Delayed Response failed", e)
            }
        }
    }

    // ── Logging ─────────────────────────────────────────────────────────

    private fun showResult(title: String, result: String) {
        Log.d(TAG, "$title: $result")
        runOnUiThread {
            httpLog = "$title:\n\n${result.take(500)}"
            updateLogView()
            Toast.makeText(this, "$title completed!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(title: String, e: Exception) {
        Log.e(TAG, title, e)
        runOnUiThread {
            httpLog = "$title:\n\nError: ${e.message}"
            updateLogView()
            Toast.makeText(this, "$title: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

// ── Retrofit API ────────────────────────────────────────────────────────

/**
 * Retrofit API interface for httpbin.org
 */
interface HttpBinApi {

    @GET("ip")
    suspend fun getIp(): IpResponse

    @GET("json")
    suspend fun getJson(): JsonResponse

    @GET("status/{code}")
    suspend fun getStatus(@Path("code") code: Int): Any

    @GET("headers")
    suspend fun getHeaders(): Any

    @GET("delay/{seconds}")
    suspend fun getDelay(@Path("seconds") seconds: Int): Any
}

// ── Response Models ─────────────────────────────────────────────────────

data class IpResponse(
    val origin: String?
)

data class JsonResponse(
    val slideshow: Slideshow?
)

data class Slideshow(
    val author: String?,
    val date: String?,
    val title: String?
)
