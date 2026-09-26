package party.morino.fukurou.engine.x11

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.InputException

class KeycodeResolverTest {
    private val fixture = javaClass.getResource("/fixtures/xmodmap-pke.txt")!!.readText()

    @Test
    @DisplayName("Unmodified keysyms resolve to their lowest keycode and the keymap is cached")
    fun resolves() = runTest {
        var loads = 0
        val resolver = KeycodeResolver { loads++; fixture }
        assertEquals(71, resolver.unmodifiedKeycode(":99", "F5"))
        assertEquals(28, resolver.unmodifiedKeycode(":99", "t"))
        assertEquals(36, resolver.unmodifiedKeycode(":99", "Return"))
        // Print は 107 と 218 にあるので小さい方
        assertEquals(107, resolver.unmodifiedKeycode(":99", "Print"))
        assertEquals(1, loads)
        resolver.invalidate(":99")
        resolver.unmodifiedKeycode(":99", "F5")
        assertEquals(2, loads)
    }

    @Test
    @DisplayName("Shifted and unknown keysyms are rejected")
    fun rejects() = runTest {
        val resolver = KeycodeResolver { fixture }
        val shifted = assertThrows<InputException> { resolver.unmodifiedKeycode(":99", "A") }
        assertEquals("A cannot be typed without modifiers; use typeText", shifted.message)
        val unknown = assertThrows<InputException> { resolver.unmodifiedKeycode(":99", "NotAKey") }
        assertEquals("unknown X11 keysym: NotAKey", unknown.message)
    }
}
