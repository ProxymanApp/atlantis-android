package com.proxyman.atlantis

import android.content.Context
import android.content.pm.PackageManager

/**
 * Configuration for Atlantis
 * Matches iOS Configuration.swift structure
 */
data class Configuration(
    val projectName: String,
    val deviceName: String,
    val packageName: String,
    val id: String,
    val hostName: String?,
    val appIcon: String?
) {
    companion object {
        /**
         * Create default configuration from Android context
         */
        fun default(context: Context, hostName: String? = null): Configuration {
            val packageName = context.packageName
            val projectName = getAppName(context)
            val deviceName = android.os.Build.MODEL
            val appIcon = AppIconHelper.getAppIconBase64(context)
            
            // Create unique ID similar to iOS: bundleIdentifier-deviceModel
            val id = "$packageName-${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
            
            return Configuration(
                projectName = projectName,
                deviceName = deviceName,
                packageName = packageName,
                id = id,
                hostName = hostName,
                appIcon = appIcon
            )
        }
        
        /**
         * Get application name from context
         */
        private fun getAppName(context: Context): String {
            return try {
                val packageManager = context.packageManager
                val applicationInfo = context.applicationInfo
                packageManager.getApplicationLabel(applicationInfo).toString()
            } catch (e: Exception) {
                context.packageName
            }
        }
    }
}
