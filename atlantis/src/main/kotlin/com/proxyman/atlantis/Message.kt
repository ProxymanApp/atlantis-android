package com.proxyman.atlantis

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Message wrapper for all data sent to Proxyman
 * Matches iOS Message.swift structure exactly
 */
data class Message(
    @SerializedName("id")
    private val id: String,
    
    @SerializedName("messageType")
    private val messageType: MessageType,
    
    @SerializedName("content")
    private val content: String?, // Base64 encoded JSON of the actual content
    
    @SerializedName("buildVersion")
    private val buildVersion: String?
) : Serializable {
    
    /**
     * Message types matching iOS implementation
     */
    enum class MessageType {
        @SerializedName("connection")
        CONNECTION,  // First message, contains: Project, Device metadata
        
        @SerializedName("traffic")
        TRAFFIC,     // Request/Response log
        
        @SerializedName("websocket")
        WEBSOCKET    // For websocket send/receive/close
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
        /**
         * Build a connection message (first message sent to Proxyman)
         */
        fun buildConnectionMessage(id: String, item: Serializable): Message {
            val contentData = item.toData()
            val contentString = contentData?.let { Base64Utils.encode(it) }
            return Message(
                id = id,
                messageType = MessageType.CONNECTION,
                content = contentString,
                buildVersion = Atlantis.BUILD_VERSION
            )
        }
        
        /**
         * Build a traffic message (HTTP request/response)
         */
        fun buildTrafficMessage(id: String, item: Serializable): Message {
            val contentData = item.toData()
            val contentString = contentData?.let { Base64Utils.encode(it) }
            return Message(
                id = id,
                messageType = MessageType.TRAFFIC,
                content = contentString,
                buildVersion = Atlantis.BUILD_VERSION
            )
        }
        
        /**
         * Build a WebSocket message
         */
        fun buildWebSocketMessage(id: String, item: Serializable): Message {
            val contentData = item.toData()
            val contentString = contentData?.let { Base64Utils.encode(it) }
            return Message(
                id = id,
                messageType = MessageType.WEBSOCKET,
                content = contentString,
                buildVersion = Atlantis.BUILD_VERSION
            )
        }
    }
}

/**
 * Interface for objects that can be serialized to JSON data
 */
interface Serializable {
    fun toData(): ByteArray?
    
    /**
     * Compress data using GZIP
     */
    fun toCompressedData(): ByteArray? {
        val rawData = toData() ?: return null
        return GzipCompression.compress(rawData) ?: rawData
    }
}
