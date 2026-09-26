package party.morino.fukurou.version

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MinecraftVersionTest {
    @Test
    @DisplayName("Versions are ordered numerically across numbering schemes")
    fun ordering() {
        assertTrue(MinecraftVersion("1.21.11") > MinecraftVersion("1.21.9"))
        assertTrue(MinecraftVersion("26.1") > MinecraftVersion("1.21.11"))
        assertTrue(MinecraftVersion("1.21") < MinecraftVersion("1.21.1"))
    }

    @Test
    @DisplayName("Pre-releases and snapshots are rejected")
    fun rejectsPreReleases() {
        assertThrows<IllegalArgumentException> { MinecraftVersion("1.21.11-rc3") }
        assertThrows<IllegalArgumentException> { MinecraftVersion("25w14a") }
        assertThrows<IllegalArgumentException> { MinecraftVersion("26") }
    }
}
