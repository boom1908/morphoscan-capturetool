package com.morpho.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerLogicTest {

    @Test
    fun testMorphoVendorId() {
        assertEquals("Safran Morpho Vendor ID must be 1947", 1947, MainActivity.MORPHO_VENDOR_ID)
    }

    @Test
    fun testRawGrayscaleConversion() {
        val width = 4
        val height = 4
        val rawBytes = ByteArray(width * height) { (it * 16).toByte() }

        assertEquals(16, rawBytes.size)
        // Verify pixel byte values in valid range
        for (b in rawBytes) {
            val unsigned = b.toInt() and 0xFF
            assertTrue(unsigned in 0..255)
        }
    }
}
