package com.proxyman.atlantis.sample

import android.app.Application
import com.proxyman.atlantis.Atlantis
import okhttp3.OkHttpClient

/**
 * Sample Application demonstrating Atlantis integration
 */
class SampleApplication : Application() {

    lateinit var okHttpClient: OkHttpClient
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Atlantis in debug builds only
        if (BuildConfig.DEBUG) {
            // Simple start - discovers all Proxyman apps on the network
            Atlantis.start(this)
            
            // Or with specific hostname:
            // Atlantis.start(this, "MacBook-Pro.local")
        }

        // Shared OkHttpClient for both HTTP + WebSocket testing
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Atlantis.getInterceptor())
            .build()
    }
}
