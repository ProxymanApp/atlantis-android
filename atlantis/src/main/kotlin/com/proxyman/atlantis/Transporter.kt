package com.proxyman.atlantis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transporter manages TCP connections to Proxyman macOS app
 * Handles service discovery, connection management, and message sending
 * 
 * Mirrors iOS Transporter.swift functionality
 */
class Transporter(
    private val context: Context
) : NsdServiceDiscovery.NsdListener {
    
    companion object {
        private const val TAG = "AtlantisTransporter"
        
        // Maximum size for a single package (50MB)
        const val MAX_PACKAGE_SIZE = 52428800
        
        // Maximum pending items to prevent memory issues
        private const val MAX_PENDING_ITEMS = 50
        
        // Connection timeout in milliseconds
        private const val CONNECTION_TIMEOUT = 10000
        
        // Retry settings for emulator
        private const val MAX_EMULATOR_RETRIES = 5
        private const val EMULATOR_RETRY_DELAY_MS = 15000L
    }
    
    private var nsdServiceDiscovery: NsdServiceDiscovery? = null
    private var config: Configuration? = null
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    
    private val pendingPackages = ConcurrentLinkedQueue<Serializable>()
    private val isConnected = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    
    private var transporterScope: CoroutineScope? = null
    private var emulatorRetryCount = 0
    
    // Listener for connection status changes
    var connectionListener: ConnectionListener? = null
    
    interface ConnectionListener {
        fun onConnected(host: String, port: Int)
        fun onDisconnected()
        fun onConnectionFailed(error: String)
    }
    
    /**
     * Start the transporter
     */
    fun start(configuration: Configuration) {
        if (isStarted.getAndSet(true)) {
            Log.d(TAG, "Transporter already started")
            return
        }
        
        config = configuration
        transporterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        
        // Check if running on emulator
        val isEmulator = isEmulator()
        
        if (isEmulator) {
            // Emulator: Direct connection to localhost:10909
            Log.d(TAG, "Running on emulator, attempting direct connection to host machine")
            connectToEmulatorHost()
        } else {
            // Real device: Use NSD to discover Proxyman
            Log.d(TAG, "Running on real device, starting NSD discovery")
            startNsdDiscovery(configuration.hostName)
        }
    }
    
    /**
     * Stop the transporter
     */
    fun stop() {
        if (!isStarted.getAndSet(false)) {
            return
        }
        
        Log.d(TAG, "Stopping transporter")
        
        // Stop NSD discovery
        nsdServiceDiscovery?.stopDiscovery()
        nsdServiceDiscovery = null
        
        // Close socket
        closeConnection()
        
        // Clear pending packages
        pendingPackages.clear()
        
        // Cancel coroutine scope
        transporterScope?.cancel()
        transporterScope = null
        
        emulatorRetryCount = 0
    }
    
    /**
     * Send a package to Proxyman
     */
    fun send(package_: Serializable) {
        if (!isStarted.get()) {
            return
        }
        
        if (!isConnected.get()) {
            // Queue the package if not connected
            appendToPendingList(package_)
            return
        }
        
        // Send immediately
        transporterScope?.launch {
            sendPackage(package_)
        }
    }
    
    // MARK: - Private Methods
    
    /**
     * Connect directly to host machine for emulator
     * Android emulator uses 10.0.2.2 to reach host's localhost
     */
    private fun connectToEmulatorHost() {
        transporterScope?.launch {
            try {
                // 10.0.2.2 is the special alias to host loopback interface
                val host = "10.0.2.2"
                val port = NsdServiceDiscovery.DIRECT_CONNECTION_PORT
                
                Log.d(TAG, "Connecting to emulator host at $host:$port")
                connectToHost(host, port)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to emulator host", e)
                handleEmulatorConnectionFailure()
            }
        }
    }
    
    /**
     * Handle emulator connection failure with retry
     */
    private fun handleEmulatorConnectionFailure() {
        if (emulatorRetryCount < MAX_EMULATOR_RETRIES) {
            emulatorRetryCount++
            Log.d(TAG, "Retrying emulator connection ($emulatorRetryCount/$MAX_EMULATOR_RETRIES) in ${EMULATOR_RETRY_DELAY_MS/1000}s...")
            
            transporterScope?.launch {
                delay(EMULATOR_RETRY_DELAY_MS)
                if (isStarted.get()) {
                    connectToEmulatorHost()
                }
            }
        } else {
            Log.e(TAG, "Maximum emulator retry limit reached. Make sure Proxyman is running on your Mac.")
            connectionListener?.onConnectionFailed("Could not connect to Proxyman. Make sure it's running on your Mac.")
        }
    }
    
    /**
     * Start NSD discovery
     */
    private fun startNsdDiscovery(hostName: String?) {
        nsdServiceDiscovery = NsdServiceDiscovery(context, this)
        nsdServiceDiscovery?.startDiscovery(hostName)
        
        if (hostName != null) {
            Log.d(TAG, "Looking for Proxyman with hostname: $hostName")
        } else {
            Log.d(TAG, "Looking for any Proxyman app on the network")
        }
    }
    
    /**
     * Connect to a specific host and port
     */
    private suspend fun connectToHost(host: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                // Close existing connection if any
                closeConnection()
                
                // Create new socket
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(host, port), CONNECTION_TIMEOUT)
                newSocket.tcpNoDelay = true
                
                socket = newSocket
                outputStream = DataOutputStream(newSocket.getOutputStream())
                
                isConnected.set(true)
                emulatorRetryCount = 0
                
                Log.d(TAG, "Connected to Proxyman at $host:$port")
                connectionListener?.onConnected(host, port)
                
                // Send connection package
                sendConnectionPackage()
                
                // Flush pending packages
                flushPendingPackages()
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $host:$port", e)
                isConnected.set(false)
                
                if (isEmulator()) {
                    handleEmulatorConnectionFailure()
                } else {
                    connectionListener?.onConnectionFailed("Connection failed: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Close the current connection
     */
    private fun closeConnection() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        } finally {
            outputStream = null
            socket = null
            isConnected.set(false)
            connectionListener?.onDisconnected()
        }
    }
    
    /**
     * Send the initial connection package
     */
    private suspend fun sendConnectionPackage() {
        val configuration = config ?: return
        
        val connectionPackage = ConnectionPackage(configuration)
        val message = Message.buildConnectionMessage(configuration.id, connectionPackage)
        
        sendPackage(message)
        Log.d(TAG, "Sent connection package")
    }
    
    /**
     * Send a package over the socket
     * Message format: [8-byte length header][GZIP compressed data]
     */
    private suspend fun sendPackage(package_: Serializable) {
        withContext(Dispatchers.IO) {
            val stream = outputStream
            if (stream == null || !isConnected.get()) {
                appendToPendingList(package_)
                return@withContext
            }
            
            try {
                // Compress the data
                val compressedData = package_.toCompressedData() ?: return@withContext
                
                // Create length header (8 bytes, UInt64)
                val lengthBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                lengthBuffer.putLong(compressedData.size.toLong())
                val headerData = lengthBuffer.array()
                
                // Send header
                stream.write(headerData)
                
                // Send compressed data
                stream.write(compressedData)
                stream.flush()
                
            } catch (e: IOException) {
                Log.e(TAG, "Error sending package", e)
                isConnected.set(false)
                appendToPendingList(package_)
                
                // Try to reconnect if this was a connection error
                if (isEmulator()) {
                    handleEmulatorConnectionFailure()
                }
            }
        }
    }
    
    /**
     * Add package to pending list
     */
    private fun appendToPendingList(package_: Serializable) {
        // Remove oldest items if limit exceeded (FIFO)
        while (pendingPackages.size >= MAX_PENDING_ITEMS) {
            pendingPackages.poll()
        }
        pendingPackages.offer(package_)
    }
    
    /**
     * Flush all pending packages
     */
    private suspend fun flushPendingPackages() {
        if (pendingPackages.isEmpty()) return
        
        Log.d(TAG, "Flushing ${pendingPackages.size} pending packages")
        
        while (pendingPackages.isNotEmpty() && isConnected.get()) {
            val package_ = pendingPackages.poll() ?: break
            sendPackage(package_)
        }
    }
    
    /**
     * Check if running on emulator
     */
    private fun isEmulator(): Boolean {
        return (android.os.Build.FINGERPRINT.startsWith("google/sdk_gphone") ||
                android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.MODEL.contains("Emulator") ||
                android.os.Build.MODEL.contains("Android SDK built for") ||
                android.os.Build.MANUFACTURER.contains("Genymotion") ||
                android.os.Build.BRAND.startsWith("generic") ||
                android.os.Build.DEVICE.startsWith("generic") ||
                "google_sdk" == android.os.Build.PRODUCT ||
                android.os.Build.HARDWARE.contains("ranchu") ||
                android.os.Build.HARDWARE.contains("goldfish"))
    }
    
    // MARK: - NsdServiceDiscovery.NsdListener
    
    override fun onServiceFound(host: InetAddress, port: Int, serviceName: String) {
        Log.d(TAG, "Proxyman service found: $serviceName at ${host.hostAddress}:$port")
        
        transporterScope?.launch {
            connectToHost(host.hostAddress ?: return@launch, port)
        }
    }
    
    override fun onServiceLost(serviceName: String) {
        Log.d(TAG, "Proxyman service lost: $serviceName")
        // Keep the connection if we're still connected
        // The socket will detect connection issues when sending
    }
    
    override fun onDiscoveryStarted() {
        Log.d(TAG, "NSD discovery started")
    }
    
    override fun onDiscoveryStopped() {
        Log.d(TAG, "NSD discovery stopped")
    }
    
    override fun onError(errorCode: Int, message: String) {
        Log.e(TAG, "NSD error ($errorCode): $message")
        connectionListener?.onConnectionFailed("NSD error: $message")
    }
}
