package party.morino.fukurou.engine.log

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.engine.step.TestDeadline
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.log.LogAssertionError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class LogWaiterTest {
    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("server.log")

    private fun append(text: String) {
        Files.writeString(log, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    // 仮想時間を乱さないよう、ファイルの読み取りもテストのスレッドで行う
    private suspend fun await(
        window: LogWindow,
        pattern: Regex,
        timeout: Duration,
        liveness: () -> Unit = {},
        deadline: TestDeadline? = null,
    ) = LogWaiter.await(
        window, pattern, timeout, null, "the server log (paper-26.3-test)", "logs/sessions/0/server.log", liveness, deadline,
        EmptyCoroutineContext,
    )

    @Test
    @DisplayName("Returns the matching line once it is written")
    fun matchesLater() = runTest {
        append("before\n")
        val window = LogWindow(log).apply { mark() }
        var polls = 0
        val match = await(window, Regex("^Alice issued server command: (/\\S+)$"), 10.seconds, liveness = {
            // 3 回目の待ちの前に行が書かれる
            if (++polls == 3) append("noise\nAlice issued server command: /st\n")
        })
        assertEquals("Alice issued server command: /st", match.line)
        assertEquals(3, match.lineNumber)
        assertEquals("/st", match.groups.groupValues[1])
        assertEquals(1500, currentTime)
    }

    @Test
    @DisplayName("Times out with the last lines and a near-miss hint")
    fun timeoutMessage() = runTest {
        val window = LogWindow(log).apply { mark() }
        append((1..9).joinToString("") { "line $it\n" } + "Alice issued server command: /st :thinking_face:\n")
        val error = assertThrows<LogAssertionError> {
            await(window, Regex("Alice issued server command: /st :thinking-face:"), 10.seconds)
        }
        val expected = """
            Timed out after 10s waiting for the server log (paper-26.3-test) to match
              /Alice issued server command: \/st :thinking-face:/
            Last 8 lines since the test started (logs/sessions/0/server.log:3-10):
              line 3
              line 4
              line 5
              line 6
              line 7
              line 8
              line 9
              Alice issued server command: /st :thinking_face:
            Hint: 1 line is a near miss (differs only in '_' vs '-').
        """.trimIndent()
        assertEquals(expected, error.message)
        assertEquals(10_000, currentTime)
    }

    @Test
    @DisplayName("A dead client fails the wait at once")
    fun livenessFails() = runTest {
        val window = LogWindow(log).apply { mark() }
        assertThrows<ClientDiedException> {
            await(window, Regex("never"), 60.seconds, liveness = { throw ClientDiedException("Alice", "Alice's client exited") })
        }
        assertEquals(0, currentTime)
    }

    @Test
    @DisplayName("The test deadline beats a longer wait timeout")
    fun deadlineBeatsTimeout() = runTest {
        val window = LogWindow(log).apply { mark() }
        val error = assertThrows<HarnessTimeoutException> {
            await(window, Regex("never"), 60.seconds, deadline = TestDeadline(1.seconds))
        }
        assertTrue(error.message!!.startsWith("the test exceeded its timeout of 1s while waiting for"), error.message)
        // 期限は実時間なので、テストの実行に掛かった分だけ短くなりうる
        assertTrue(currentTime <= 1000, "virtual time $currentTime")
    }
}
