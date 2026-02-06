package com.proxyman.atlantis

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Connection package sent as the first message to Proxyman
 * Contains device and project metadata
 */
data class ConnectionPackage(
    @SerializedName("device")
    val device: Device,
    
    @SerializedName("project")
    val project: Project,
    
    @SerializedName("icon")
    val icon: String? // Base64 encoded PNG
) : Serializable {
    
    constructor(config: Configuration) : this(
        device = Device.current(config.deviceName),
        project = Project.current(config.projectName, config.packageName),
        icon = config.appIcon
    )
    
    override fun toData(): ByteArray? {
        return try {
            Gson().toJson(this).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

/**
 * Traffic package containing HTTP request/response data
 */
data class TrafficPackage(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("startAt")
    var startAt: Double,
    
    @SerializedName("request")
    val request: Request,
    
    @SerializedName("response")
    var response: Response? = null,
    
    @SerializedName("error")
    var error: CustomError? = null,
    
    @SerializedName("responseBodyData")
    var responseBodyData: String = "", // Base64 encoded
    
    @SerializedName("endAt")
    var endAt: Double? = null,
    
    @SerializedName("packageType")
    val packageType: PackageType = PackageType.HTTP,
    
    @SerializedName("websocketMessagePackage")
    var websocketMessagePackage: WebsocketMessagePackage? = null
) : Serializable {
    
    enum class PackageType {
        @SerializedName("http")
        HTTP,
        
        @SerializedName("websocket")
        WEBSOCKET
    }
    
    override fun toData(): ByteArray? {
        return try {
            Gson().toJson(this).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    companion object {
        private const val MAX_BODY_SIZE = 52428800 // 50MB
        
        /**
         * Create a new TrafficPackage with a unique ID
         */
        fun create(request: Request): TrafficPackage {
            return TrafficPackage(
                id = UUID.randomUUID().toString(),
                startAt = System.currentTimeMillis() / 1000.0,
                request = request,
                packageType = PackageType.HTTP
            )
        }
        
        /**
         * Create a new WebSocket TrafficPackage with a unique ID
         */
        fun createWebSocket(request: Request): TrafficPackage {
            return TrafficPackage(
                id = UUID.randomUUID().toString(),
                startAt = System.currentTimeMillis() / 1000.0,
                request = request,
                packageType = PackageType.WEBSOCKET
            )
        }
    }
}

/**
 * Device information
 */
data class Device(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("model")
    val model: String
) {
    companion object {
        fun current(customName: String? = null): Device {
            val deviceName = customName ?: Build.MODEL ?: "Unknown Device"
            val manufacturer = Build.MANUFACTURER ?: "Unknown"
            val model = Build.MODEL ?: "Unknown"
            val release = Build.VERSION.RELEASE ?: "Unknown"
            val fullModel = "$manufacturer $model (Android $release)"
            return Device(name = deviceName, model = fullModel)
        }
    }
}

/**
 * Project/App information
 */
data class Project(
    @SerializedName("name")
    val name: String,
    
    @SerializedName("bundleIdentifier")
    val bundleIdentifier: String
) {
    companion object {
        fun current(customName: String? = null, packageName: String): Project {
            return Project(
                name = customName ?: packageName,
                bundleIdentifier = packageName
            )
        }
    }
}

/**
 * HTTP Header
 */
data class Header(
    @SerializedName("key")
    val key: String,
    
    @SerializedName("value")
    val value: String
)

/**
 * HTTP Request
 */
data class Request(
    @SerializedName("url")
    val url: String,
    
    @SerializedName("method")
    val method: String,
    
    @SerializedName("headers")
    val headers: List<Header>,
    
    @SerializedName("body")
    var body: String? = null // Base64 encoded
) {
    companion object {
        private const val MAX_BODY_SIZE = 52428800 // 50MB
        
        /**
         * Create from OkHttp request components
         */
        fun fromOkHttp(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: ByteArray?
        ): Request {
            val headerList = headers.map { Header(it.key, it.value) }
            val bodyString = if (body != null && body.size <= MAX_BODY_SIZE) {
                Base64Utils.encode(body)
            } else {
                null
            }
            return Request(
                url = url,
                method = method,
                headers = headerList,
                body = bodyString
            )
        }
    }
}

/**
 * HTTP Response
 */
data class Response(
    @SerializedName("statusCode")
    val statusCode: Int,
    
    @SerializedName("headers")
    val headers: List<Header>
) {
    companion object {
        /**
         * Create from OkHttp response components
         */
        fun fromOkHttp(statusCode: Int, headers: Map<String, String>): Response {
            val headerList = headers.map { Header(it.key, it.value) }
            return Response(statusCode = statusCode, headers = headerList)
        }
    }
}

/**
 * Custom error for failed requests
 */
data class CustomError(
    @SerializedName("code")
    val code: Int,
    
    @SerializedName("message")
    val message: String
) {
    companion object {
        fun fromException(e: Exception): CustomError {
            return CustomError(
                code = -1,
                message = e.message ?: "Unknown error"
            )
        }
    }
}

/**
 * WebSocket message package
 */
data class WebsocketMessagePackage(
    @SerializedName("id")
    private val id: String,
    
    @SerializedName("createdAt")
    private val createdAt: Double,
    
    @SerializedName("messageType")
    private val messageType: MessageType,
    
    @SerializedName("stringValue")
    private val stringValue: String?,
    
    @SerializedName("dataValue")
    private val dataValue: String? // Base64 encoded
) : Serializable {
    
    enum class MessageType {
        @SerializedName("pingPong")
        PING_PONG,
        
        @SerializedName("send")
        SEND,
        
        @SerializedName("receive")
        RECEIVE,
        
        @SerializedName("sendCloseMessage")
        SEND_CLOSE_MESSAGE
    }
    
    override fun toData(): ByteArray? {
        return try {
            Gson().toJson(this).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    companion object {
        fun createStringMessage(id: String, message: String, type: MessageType): WebsocketMessagePackage {
            return WebsocketMessagePackage(
                id = id,
                createdAt = System.currentTimeMillis() / 1000.0,
                messageType = type,
                stringValue = message,
                dataValue = null
            )
        }
        
        fun createDataMessage(id: String, data: ByteArray, type: MessageType): WebsocketMessagePackage {
            return WebsocketMessagePackage(
                id = id,
                createdAt = System.currentTimeMillis() / 1000.0,
                messageType = type,
                stringValue = null,
                dataValue = Base64Utils.encode(data)
            )
        }
        
        fun createCloseMessage(id: String, closeCode: Int, reason: String?): WebsocketMessagePackage {
            return WebsocketMessagePackage(
                id = id,
                createdAt = System.currentTimeMillis() / 1000.0,
                messageType = MessageType.SEND_CLOSE_MESSAGE,
                stringValue = closeCode.toString(),
                dataValue = reason?.let { Base64Utils.encode(it.toByteArray()) }
            )
        }
    }
}

/**
 * Helper to get app icon as Base64 PNG
 */
internal object AppIconHelper {
    fun getAppIconBase64(context: Context): String? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = context.applicationInfo
            val drawable = packageManager.getApplicationIcon(applicationInfo)
            
            if (drawable is BitmapDrawable) {
                val bitmap = drawable.bitmap
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val byteArray = stream.toByteArray()
                Base64Utils.encode(byteArray)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
