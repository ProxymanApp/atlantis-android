package com.proxyman.atlantis

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress

/**
 * Network Service Discovery (NSD) for finding Proxyman app on local network
 * This is Android's equivalent of iOS Bonjour
 */
class NsdServiceDiscovery(
    private val context: Context,
    private val listener: NsdListener
) {
    
    companion object {
        private const val TAG = "AtlantisNSD"
        
        // Service type must match iOS: _Proxyman._tcp
        const val SERVICE_TYPE = "_Proxyman._tcp."
        
        // Direct connection port for emulator
        const val DIRECT_CONNECTION_PORT = 10909
    }
    
    interface NsdListener {
        fun onServiceFound(host: InetAddress, port: Int, serviceName: String)
        fun onServiceLost(serviceName: String)
        fun onDiscoveryStarted()
        fun onDiscoveryStopped()
        fun onError(errorCode: Int, message: String)
    }
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    private var targetHostName: String? = null
    
    /**
     * Start discovering Proxyman services on the network
     * @param hostName Optional hostname to filter services (like iOS hostName parameter)
     */
    fun startDiscovery(hostName: String? = null) {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress")
            return
        }
        
        targetHostName = hostName
        
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            
            discoveryListener = createDiscoveryListener()
            nsdManager?.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            
            Log.d(TAG, "Starting NSD discovery for Proxyman services...")
            if (hostName != null) {
                Log.d(TAG, "Looking for specific host: $hostName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
            listener.onError(-1, "Failed to start discovery: ${e.message}")
        }
    }
    
    /**
     * Stop discovering services
     */
    fun stopDiscovery() {
        if (!isDiscovering) {
            return
        }
        
        try {
            discoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping NSD discovery", e)
        } finally {
            isDiscovering = false
            discoveryListener = null
        }
    }
    
    /**
     * Create the discovery listener
     */
    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started for: $serviceType")
                isDiscovering = true
                listener.onDiscoveryStarted()
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped for: $serviceType")
                isDiscovering = false
                listener.onDiscoveryStopped()
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                
                // Check if we should connect to this service based on hostname
                if (shouldConnectToService(serviceInfo.serviceName)) {
                    resolveService(serviceInfo)
                } else {
                    Log.d(TAG, "Skipping service: ${serviceInfo.serviceName} (hostname filter active)")
                }
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                listener.onServiceLost(serviceInfo.serviceName)
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                isDiscovering = false
                listener.onError(errorCode, "Discovery start failed")
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
                listener.onError(errorCode, "Discovery stop failed")
            }
        }
    }
    
    /**
     * Resolve a discovered service to get its host and port
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }
            
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName}")
                Log.d(TAG, "  Host: ${resolvedInfo.host}")
                Log.d(TAG, "  Port: ${resolvedInfo.port}")
                
                resolvedInfo.host?.let { host ->
                    listener.onServiceFound(
                        host = host,
                        port = resolvedInfo.port,
                        serviceName = resolvedInfo.serviceName
                    )
                }
            }
        }
        
        try {
            nsdManager?.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving service", e)
        }
    }
    
    /**
     * Check if we should connect to this service based on hostname filter
     * Mirrors iOS shouldConnectToEndpoint logic
     */
    private fun shouldConnectToService(serviceName: String): Boolean {
        val requiredHost = targetHostName ?: return true
        
        val lowercasedRequiredHost = requiredHost.lowercase().removeSuffix(".")
        val lowercasedServiceName = serviceName.lowercase()
        
        // Allow connection if the service name contains the required host
        // This handles cases like required="mac-mini.local" and service="Proxyman-mac-mini.local"
        return lowercasedServiceName.contains(lowercasedRequiredHost)
    }
    
    /**
     * Check if running on an emulator
     */
    fun isEmulator(): Boolean {
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
}
