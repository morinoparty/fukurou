package party.morino.fukurou.engine.paper.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PaperPlayerCommandsTest {
    @Test
    @DisplayName("Rotate plans a single in-place teleport for the player")
    fun rotate() {
        val calls = PaperPlayerCommands.rotate("Alice", 180f, 30f)
        assertEquals(listOf("execute as Alice at @s run tp @s ~ ~ ~ 180 30"), calls.map { it.command })
        assertEquals(listOf("Alice"), calls.map { it.player })
    }
}
