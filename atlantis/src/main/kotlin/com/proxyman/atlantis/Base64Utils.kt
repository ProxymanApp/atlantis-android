package com.proxyman.atlantis

import android.util.Base64

/**
 * Utility for Base64 encoding.
 *
 * Uses android.util.Base64 (available since API 8) rather than java.util.Base64 (API 26+), to
 * keep this library's minSdk low. android.util.Base64's real implementation isn't present in the
 * JVM used for plain JUnit tests, so calls are mocked there, see Base64MockRule.
 */
internal object Base64Utils {

    /**
     * Encode bytes to Base64 string without line wrapping
     */
    fun encode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /**
     * Decode Base64 string to bytes
     */
    fun decode(encoded: String): ByteArray {
        return Base64.decode(encoded, Base64.NO_WRAP)
    }
}
