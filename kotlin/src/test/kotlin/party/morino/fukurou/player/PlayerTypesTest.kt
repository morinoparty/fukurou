package party.morino.fukurou.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale

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
    @DisplayName("The locale becomes a Minecraft language code and needs a country")
    fun locales() {
        assertEquals("en_us", PlayerProfile("Alice").minecraftLanguage)
        assertEquals("ja_jp", PlayerProfile("Alice", locale = Locale.JAPAN).minecraftLanguage)
        assertEquals("ja_jp", PlayerProfile("Alice", locale = Locale.forLanguageTag("ja-JP")).minecraftLanguage)
        assertEquals("fil_ph", PlayerProfile("Alice", locale = Locale.forLanguageTag("fil-PH")).minecraftLanguage)
        listOf(Locale.JAPANESE, Locale.ROOT).forEach { locale ->
            assertThrows<IllegalArgumentException>(locale.toString()) { PlayerProfile("Alice", locale = locale) }
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
