package party.morino.fukurou.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlayerTypesTest {
    @Test
    @DisplayName("Player names follow the Minecraft rule and 'server' is reserved")
    fun playerNames() {
        assertEquals("Alice_01", PlayerProfile("Alice_01").name)
        listOf("Al", "Alice Bob", "a".repeat(17), "server").forEach { name ->
            assertThrows<IllegalArgumentException>(name) { PlayerProfile(name) }
        }
    }

    @Test
    @DisplayName("Key aliases map to X11 keysyms and chords join with plus")
    fun keySyms() {
        assertEquals(KeySym.ENTER, KeySym.of("Enter"))
        assertEquals(KeySym("space"), KeySym.of("Space"))
        assertEquals(KeySym.F5, KeySym.of("F5"))
        assertEquals("F3+d", (KeySym.F3 + KeySym.D).toString())
        assertThrows<IllegalArgumentException> { KeySym("F3+d") }
    }
}
