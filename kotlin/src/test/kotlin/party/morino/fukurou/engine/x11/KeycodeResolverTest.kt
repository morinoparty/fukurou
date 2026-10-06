package party.morino.fukurou.engine.x11

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.InputException
import party.morino.fukurou.player.KeySym

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

    @Test
    @DisplayName("Movement, modifier, arrow and digit KeySym constants resolve without modifiers in the fixture keymap")
    fun keySymConstants() = runTest {
        val resolver = KeycodeResolver { fixture }
        val expected = linkedMapOf(
            KeySym.W to 25, KeySym.A to 38, KeySym.S to 39, KeySym.D to 40,
            KeySym.E to 26, KeySym.Q to 24, KeySym.F to 41,
            KeySym.SHIFT to 50, KeySym.CONTROL to 37, KeySym.BACKSPACE to 22,
            KeySym.UP to 111, KeySym.DOWN to 116, KeySym.LEFT to 113, KeySym.RIGHT to 114,
            KeySym.DIGIT_1 to 10, KeySym.DIGIT_2 to 11, KeySym.DIGIT_3 to 12, KeySym.DIGIT_4 to 13, KeySym.DIGIT_5 to 14,
            KeySym.DIGIT_6 to 15, KeySym.DIGIT_7 to 16, KeySym.DIGIT_8 to 17, KeySym.DIGIT_9 to 18,
        )
        for ((key, keycode) in expected) {
            assertEquals(keycode, resolver.unmodifiedKeycode(":99", key.keysym), key.keysym)
        }
    }

    @Test
    @DisplayName("Digit keys are sent as their two-digit keycodes, never as the digit itself")
    fun digitTokens() = runTest {
        val resolver = KeycodeResolver { fixture }
        val tokens = listOf(KeySym.DIGIT_1, KeySym.DIGIT_5, KeySym.DIGIT_9).map {
            MinecraftWindow.keyToken(it.keysym, resolver.unmodifiedKeycode(":99", it.keysym))
        }
        // "1" をそのまま渡すと xdotool は keysym の 1 と読むが、キーコードの 10 を渡せば修飾キーなしの 1 になる
        assertEquals(listOf("10", "14", "18"), tokens)
    }
}
