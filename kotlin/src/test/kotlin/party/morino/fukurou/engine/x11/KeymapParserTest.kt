package party.morino.fukurou.engine.x11

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class KeymapParserTest {
    @Test
    @DisplayName("xmodmap -pke lines become ordered keycode columns")
    fun parsesFixture() {
        val keymap = KeymapParser.parse(javaClass.getResource("/fixtures/xmodmap-pke.txt")!!.readText())
        assertEquals(8, keymap.keys.first())
        assertEquals(emptyList<String>(), keymap[8])
        assertEquals("F5", keymap[71]!!.first())
        assertEquals(listOf("t", "T", "t", "T"), keymap[28])
        assertEquals(listOf("Return", "NoSymbol", "Return"), keymap[36])
    }

    @Test
    @DisplayName("NoSymbol never matches")
    fun noSymbol() {
        assertFalse(KeymapParser.matches("NoSymbol", "NoSymbol"))
    }
}
