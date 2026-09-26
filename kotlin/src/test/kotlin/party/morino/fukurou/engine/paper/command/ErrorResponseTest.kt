package party.morino.fukurou.engine.paper.command

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.spi.model.CommandCall

class ErrorResponseTest {
    private fun isError(response: String?, vararg ignore: String) =
        ErrorResponse.isError(CommandCall("cmd", ignore = ignore.toList()), response)

    @Test
    @DisplayName("Each error prefix is an error, anchored at a line start")
    fun prefixes() {
        assertTrue(isError("Too many blocks in the specified area (32769 > 32768)"))
        assertTrue(isError("Unknown or incomplete command, see below for error\ntp Alice<--[HERE]"))
        assertTrue(isError("Incorrect argument for command"))
        assertTrue(isError("Cannot kill that entity"))
        assertTrue(isError("That position is not loaded"))
        assertTrue(isError("No player was found"))
        // MULTILINE: 2 行目の行頭でも見つかる
        assertTrue(isError("first line\nNo player was found"))
        // 行頭でなければエラーではない
        assertFalse(isError("Result: Too many blocks"))
    }

    @Test
    @DisplayName("Success responses, ignored fragments and null are not errors")
    fun notErrors() {
        assertFalse(isError("Successfully filled 8 blocks"))
        assertFalse(isError("Teleported Alice to 0.5, -60.0, -8.5"))
        assertFalse(isError("Target either has no effects to remove, or has effects that cannot be removed"))
        assertFalse(isError("No entity was found", "No entity was found"))
        assertFalse(isError("Nothing changed. The player already is an operator", "Nothing changed"))
        assertFalse(isError(null))
    }
}
