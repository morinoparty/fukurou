package party.morino.fukurou.engine.paper.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.EOFException

class RconCodecTest {
    @Test
    @DisplayName("Encodes little-endian packets that decode back")
    fun roundTrip() {
        val bytes = RconCodec.encode(7, RconCodec.COMMAND_TYPE, "say こんにちは")
        // 長さ = ID + 種別 + ペイロード + 終端 2 バイト
        assertEquals(bytes.size - 4, bytes[0].toInt() and 0xff)
        assertEquals(RconCodec.Packet(7, 2, "say こんにちは"), RconCodec.decode(ByteArrayInputStream(bytes)))
    }

    @Test
    @DisplayName("Rejects payloads over 1446 bytes before sending")
    fun sizeLimit() {
        RconCodec.encode(1, RconCodec.COMMAND_TYPE, "a".repeat(1446))
        val error = assertThrows<IllegalArgumentException> { RconCodec.encode(1, RconCodec.COMMAND_TYPE, "a".repeat(1447)) }
        assertEquals("command is 1447 bytes; RCON accepts at most 1446 — shorten the component", error.message)
    }

    @Test
    @DisplayName("A truncated packet is an EOF")
    fun truncated() {
        val bytes = RconCodec.encode(1, RconCodec.COMMAND_TYPE, "list")
        assertThrows<EOFException> { RconCodec.decode(ByteArrayInputStream(bytes.copyOf(bytes.size - 3))) }
    }
}
