package com.proxyman.atlantis

/**
 * Utility for Base64 encoding that works in both Android runtime and JUnit tests.
 * 
 * Android's android.util.Base64 is not available in unit tests (only instrumented tests),
 * so we use java.util.Base64 which is available everywhere since API 26.
 */
internal object Base64Utils {
    
    /**
     * Encode bytes to Base64 string without line wrapping
     */
    fun encode(data: ByteArray): String {
        return java.util.Base64.getEncoder().encodeToString(data)
    }
    
    /**
     * Decode Base64 string to bytes
     */
    fun decode(encoded: String): ByteArray {
        return java.util.Base64.getDecoder().decode(encoded)
    }
}
