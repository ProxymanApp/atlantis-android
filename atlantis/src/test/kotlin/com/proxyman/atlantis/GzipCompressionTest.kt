package com.proxyman.atlantis

import org.junit.Assert.*
import org.junit.Test

class GzipCompressionTest {
    
    @Test
    fun `test compress and decompress`() {
        val original = "Hello, World! This is a test message for compression."
        val originalBytes = original.toByteArray(Charsets.UTF_8)
        
        // Compress
        val compressed = GzipCompression.compress(originalBytes)
        assertNotNull(compressed)
        
        // Verify it's actually compressed (should start with gzip magic bytes)
        assertTrue(GzipCompression.isGzipped(compressed!!))
        
        // Decompress
        val decompressed = GzipCompression.decompress(compressed)
        assertNotNull(decompressed)
        
        // Verify content matches
        assertEquals(original, decompressed!!.toString(Charsets.UTF_8))
    }
    
    @Test
    fun `test compress empty data`() {
        val empty = ByteArray(0)
        val result = GzipCompression.compress(empty)
        
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }
    
    @Test
    fun `test decompress empty data`() {
        val empty = ByteArray(0)
        val result = GzipCompression.decompress(empty)
        
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }
    
    @Test
    fun `test isGzipped with valid gzip data`() {
        val data = "Test data".toByteArray()
        val compressed = GzipCompression.compress(data)
        
        assertTrue(GzipCompression.isGzipped(compressed!!))
    }
    
    @Test
    fun `test isGzipped with non-gzip data`() {
        val data = "Not compressed".toByteArray()
        
        assertFalse(GzipCompression.isGzipped(data))
    }
    
    @Test
    fun `test isGzipped with short data`() {
        val shortData = byteArrayOf(0x1f) // Only 1 byte
        
        assertFalse(GzipCompression.isGzipped(shortData))
    }
    
    @Test
    fun `test compression reduces size for large data`() {
        // Create a large repetitive string (compresses well)
        val largeData = "A".repeat(10000).toByteArray()
        val compressed = GzipCompression.compress(largeData)
        
        assertNotNull(compressed)
        assertTrue("Compressed size should be smaller", compressed!!.size < largeData.size)
    }
    
    @Test
    fun `test decompress invalid data returns null`() {
        val invalidData = "This is not valid gzip data".toByteArray()
        
        // Mark as "gzip" by adding magic bytes but with invalid content
        val fakeGzip = byteArrayOf(0x1f, 0x8b.toByte()) + invalidData
        
        // Should return null for invalid gzip
        val result = GzipCompression.decompress(fakeGzip)
        assertNull(result)
    }
    
    @Test
    fun `test roundtrip with JSON data`() {
        val jsonData = """
            {
                "id": "test-123",
                "name": "Test Package",
                "data": {
                    "nested": true,
                    "values": [1, 2, 3, 4, 5]
                }
            }
        """.trimIndent()
        
        val originalBytes = jsonData.toByteArray(Charsets.UTF_8)
        val compressed = GzipCompression.compress(originalBytes)
        val decompressed = GzipCompression.decompress(compressed!!)
        
        assertEquals(jsonData, decompressed!!.toString(Charsets.UTF_8))
    }
    
    @Test
    fun `test roundtrip with binary data`() {
        // Create some binary data
        val binaryData = ByteArray(256) { it.toByte() }
        
        val compressed = GzipCompression.compress(binaryData)
        val decompressed = GzipCompression.decompress(compressed!!)
        
        assertArrayEquals(binaryData, decompressed)
    }
}
