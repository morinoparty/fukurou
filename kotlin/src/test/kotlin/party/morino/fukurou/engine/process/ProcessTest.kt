package party.morino.fukurou.engine.process

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.error.InputException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessTest {
    @TempDir
    lateinit var dir: Path

    @Test
    @DisplayName("The pgrp is read after the command field")
    fun parsesStat() {
        assertEquals(42L, ProcessLauncher.parsePgrp("41 (a) b (c)) S 1 42 42 0 -1"))
    }

    @Test
    @DisplayName("A launched process leads its group and stop kills its children")
    fun launchAndStop() = runBlocking {
        val log = dir.resolve("logs/out.log")
        val process = ProcessLauncher.launch("test", listOf("sh", "-c", "echo started; sleep 60 & wait"), dir, log)
        assertTrue(process.groupKill)
        assertEquals(process.pid, process.pgid)
        // 子の sleep が起動するまで待つ
        while (process.process.descendants().count() == 0L) Thread.sleep(10)
        val children = process.process.descendants().toList()
        process.stop(5.seconds)
        assertFalse(process.isAlive)
        assertTrue(children.none { it.isAlive })
        assertEquals("started", Files.readString(log).trim())
    }

    @Test
    @DisplayName("Short tools capture output and time out with InputException")
    fun externalCommand() = runBlocking {
        val result = ExternalCommand.run(listOf("sh", "-c", "echo out; echo err >&2; exit 3"))
        assertEquals(ExternalCommand.Result(3, "out\n", "err\n"), result)
        val error = assertThrows<InputException> { runBlocking { ExternalCommand.run(listOf("sleep", "10"), timeout = 200.milliseconds) } }
        assertEquals("sleep 10 timed out after 0s", error.message)
    }

    @Test
    @DisplayName("HostCheck lists every missing item")
    fun hostCheck() {
        val problems = HostCheck.problems("Mac OS X", "aarch64", "")
        assertEquals(7, problems.size)
        assertTrue(HostCheck.message(problems).contains("x11-xserver-utils"))
    }
}
