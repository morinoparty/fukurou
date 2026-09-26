package party.morino.fukurou.version

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VersionSpecTest {
    @Test
    @DisplayName("Open range includes every later version")
    fun openRange() {
        val spec = VersionSpec.parse("1.21.6-")
        assertTrue(spec.contains(MinecraftVersion("1.21.6")))
        assertTrue(spec.contains(MinecraftVersion("26.3")))
        assertFalse(spec.contains(MinecraftVersion("1.21.5")))
    }

    @Test
    @DisplayName("Closed range and single version")
    fun closedRangeAndSingle() {
        val range = VersionSpec.parse("1.21.6-1.21.11")
        assertTrue(range.contains(MinecraftVersion("1.21.11")))
        assertFalse(range.contains(MinecraftVersion("26.1")))
        val single = VersionSpec.parse("1.21.9")
        assertTrue(single.contains(MinecraftVersion("1.21.9")))
        assertFalse(single.contains(MinecraftVersion("1.21.10")))
    }

    @Test
    @DisplayName("Malformed specs are rejected")
    fun malformed() {
        listOf("", "latest", "-1.21.6", "1.21.6-1.21.7-1.21.8", "1.21.11-1.21.6").forEach { text ->
            assertThrows<IllegalArgumentException>(text) { VersionSpec.parse(text) }
        }
    }
}
