package party.morino.fukurou.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocationTest {
    @Test
    @DisplayName("Command args drop trailing zeros like Python's :g")
    fun commandArgs() {
        assertEquals("0.5 -49 0.5 0 30", Location(0.5, -49.0, 0.5, yaw = 0f, pitch = 30f).toCommandArgs())
        assertEquals("10 64 -8.5", Location(10.0, 64.0, -8.5).toCommandArgs())
        assertEquals("0 0 0 0.1 -90", Location(0.0, 0.0, 0.0, yaw = 0.1f, pitch = -90f).toCommandArgs())
    }

    @Test
    @DisplayName("Yaw and pitch must be given together")
    fun yawPitchPairing() {
        assertThrows<IllegalArgumentException> { Location(0.0, 0.0, 0.0, yaw = 90f) }
    }
}
