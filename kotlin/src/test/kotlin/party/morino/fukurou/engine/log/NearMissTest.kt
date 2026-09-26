package party.morino.fukurou.engine.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class NearMissTest {
    @Test
    @DisplayName("Hints at an underscore written instead of a hyphen")
    fun underscoreVsHyphen() {
        val hint = NearMiss.hint(
            Regex("""Alice issued server command: \/st :thinking-face:"""),
            listOf("[12:00:00 INFO]: Done", "[12:00:01 INFO]: Alice issued server command: /st :thinking_face:"),
        )
        assertEquals("Hint: 1 line is a near miss (differs only in '_' vs '-').", hint)
    }

    @Test
    @DisplayName("No hint when no line is one edit away")
    fun noNearMiss() {
        assertNull(NearMiss.hint(Regex("joined the game"), listOf("Bob left the game")))
    }

    @Test
    @DisplayName("Skips patterns of 200 characters or more")
    fun longPattern() {
        val fragment = "a".repeat(200)
        assertNull(NearMiss.hint(Regex(fragment), listOf("a".repeat(199) + "b")))
    }

    @Test
    @DisplayName("Takes the longest literal fragment, dropping optional characters")
    fun longestLiteral() {
        assertEquals("[CHAT] Stamp sent: ", NearMiss.longestLiteral("""\[CHAT\] Stamp sent: \w+"""))
        assertEquals(" or incomplete", NearMiss.longestLiteral("""Unknown( or incomplete)? command"""))
        assertEquals("Stamp", NearMiss.longestLiteral("""Stamps? [a-z]+"""))
        assertNull(NearMiss.longestLiteral("""\d+\.\d+"""))
    }
}
