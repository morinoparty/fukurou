package party.morino.fukurou.engine.step

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals

class LaneGuardTest {
    @Test
    @DisplayName("Sixteen lanes are allowed and seventeen are rejected")
    fun laneCount() {
        assertDoesNotThrow { LaneGuard.checkLaneCount(16) }
        assertThrows<IllegalArgumentException> { LaneGuard.checkLaneCount(17) }
    }

    @Test
    @DisplayName("A parallel block inside another is rejected")
    fun nesting() {
        assertDoesNotThrow { LaneGuard.checkNotNested(null) }
        assertThrows<IllegalStateException> { LaneGuard.checkNotNested(0) }
    }

    @Test
    @DisplayName("Only one lane of a block may send input to a player")
    fun inputOwner() {
        val guard = LaneGuard(2)
        guard.onInput("Alice", 0)
        guard.onInput("Alice", 0)
        guard.onInput("Bob", 1)
        val error = assertThrows<IllegalStateException> { guard.onInput("Alice", 1) }
        assertEquals("two lanes of parallel block 2 sent input to Alice", error.message)
    }
}
