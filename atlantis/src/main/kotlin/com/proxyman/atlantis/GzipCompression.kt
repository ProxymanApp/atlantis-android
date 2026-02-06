package com.proxyman.atlantis

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * GZIP compression utilities
 * Matches iOS DataCompression.swift functionality
 */
object GzipCompression {
    
    /**
     * Compress data using GZIP
     * @param data The raw data to compress
     * @return Compressed data or null if compression fails
     */
    fun compress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return data
        
        return try {
            val outputStream = ByteArrayOutputStream()
            GZIPOutputStream(outputStream).use { gzipStream ->
                gzipStream.write(data)
            }
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Decompress GZIP data
     * @param data The compressed data
     * @return Decompressed data or null if decompression fails
     */
    fun decompress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return data
        
        return try {
            val inputStream = ByteArrayInputStream(data)
            GZIPInputStream(inputStream).use { gzipStream ->
                gzipStream.readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if data is GZIP compressed
     * GZIP magic number: 0x1f 0x8b
     */
    fun isGzipped(data: ByteArray): Boolean {
        return data.size >= 2 && 
               data[0] == 0x1f.toByte() && 
               data[1] == 0x8b.toByte()
    }
}
