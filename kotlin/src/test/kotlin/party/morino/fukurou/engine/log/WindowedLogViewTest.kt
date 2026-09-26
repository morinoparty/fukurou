package party.morino.fukurou.engine.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.log.LogAssertionError
import java.nio.file.Files
import java.nio.file.Path

class WindowedLogViewTest {
    @TempDir
    lateinit var dir: Path

    @Test
    @DisplayName("assertAbsent lists every matching line with its line number")
    fun assertAbsent() {
        val log = dir.resolve("Alice.log")
        Files.writeString(log, "old [CHAT] Unknown command\n")
        val window = LogWindow(log).apply { mark() }
        val view = WindowedLogView({ window }, "Alice's client log", { "logs/sessions/0/clients/Alice.log" }, {}, { null })
        Files.writeString(log, "old [CHAT] Unknown command\nok\n[CHAT] Unknown command\n")
        val after = view.mark()
        assertDoesNotThrow { view.assertAbsent(Regex("Unknown command"), after) }
        val error = assertThrows<LogAssertionError> { view.assertAbsent(Regex("^\\[CHAT\\].*Unknown command")) }
        val expected = """
            Alice's client log has 1 line matching
              /^\[CHAT\].*Unknown command/
            since the test started (logs/sessions/0/clients/Alice.log:3):
              [CHAT] Unknown command
        """.trimIndent()
        assertEquals(expected, error.message)
    }
}
