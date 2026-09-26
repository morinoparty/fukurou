package party.morino.fukurou.engine.session

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PngHeaderTest {
    /** 署名 + IHDR の長さと型 + 幅 + 高さ。 */
    private fun header(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(24)
            .put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            .putInt(13)
            .put("IHDR".toByteArray())
            .putInt(width)
            .putInt(height)
            .array()

    @Test
    @DisplayName("The IHDR width and height are read big-endian")
    fun size() {
        assertTrue(PngHeader.isPng(header(1280, 720)))
        assertEquals(1280 to 720, PngHeader.size(header(1280, 720)))
    }

    @Test
    @DisplayName("A file without the PNG signature is rejected")
    fun notPng() {
        val bytes = header(1, 1).also { it[0] = 0 }
        assertFalse(PngHeader.isPng(bytes))
        assertThrows<IllegalArgumentException> { PngHeader.size(bytes) }
        assertThrows<IllegalArgumentException> { PngHeader.size(ByteArray(8)) }
    }
}
