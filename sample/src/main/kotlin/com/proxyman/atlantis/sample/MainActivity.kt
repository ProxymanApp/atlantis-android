package com.proxyman.atlantis.sample

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.proxyman.atlantis.Atlantis
import com.proxyman.atlantis.Transporter
import com.proxyman.atlantis.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Main Activity demonstrating Atlantis with OkHttp and Retrofit
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "AtlantisSample"
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
    
    private fun setupUI() {
        binding.btnGetRequest.setOnClickListener {
            makeGetRequest()
        }
        
        binding.btnPostRequest.setOnClickListener {
            makePostRequest()
        }
        
        binding.btnRetrofitRequest.setOnClickListener {
            makeRetrofitRequest()
        }
        
        binding.btnJsonRequest.setOnClickListener {
            makeJsonRequest()
        }
        
        binding.btnErrorRequest.setOnClickListener {
            makeErrorRequest()
        }

        binding.btnStartWebSocketTest.setOnClickListener {
            WebSocketTestController.startAutoTest(okHttpClient)
        }
        
        updateStatus()
        updateLogView()
    }
    
    private fun updateStatus() {
        val status = if (!Atlantis.isRunning()) {
            "Atlantis is not running"
        } else {
            val detail = connectionState ?: "Looking for Proxyman..."
            "Atlantis is running.\n$detail"
        }
        binding.tvStatus.text = status
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
                append("=== HTTP ===\n")
                append(httpLog)
                append("\n\n")
            }
            append("=== WebSocket (auto every 1s) ===\n")
            append(if (wsLog.isNotBlank()) wsLog else "(no websocket logs yet)")
        }
        binding.tvResult.text = combined
    }
    
    private fun makeGetRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("https://httpbin.org/get")
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
                    val jsonBody = """{"name": "Atlantis", "platform": "Android"}"""
                    val body = jsonBody.toRequestBody("application/json".toMediaType())
                    
                    val request = Request.Builder()
                        .url("https://httpbin.org/post")
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
    
    private fun makeJsonRequest() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpBinApi.getJson()
                }
                showResult("JSON Request", "Slideshow title: ${result.slideshow?.title}")
            } catch (e: Exception) {
                showError("JSON Request failed", e)
            }
        }
    }
    
    private fun makeErrorRequest() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("https://httpbin.org/status/404")
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("HTTP ${response.code}: ${response.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                showError("Error Request (expected)", e)
            }
        }
    }
    
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
}

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
