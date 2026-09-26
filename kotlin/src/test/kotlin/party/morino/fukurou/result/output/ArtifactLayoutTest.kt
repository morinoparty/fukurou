package party.morino.fukurou.result.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.error.SetupException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText

class ArtifactLayoutTest {
    @TempDir
    lateinit var out: Path

    @Test
    @DisplayName("relative() rejects parent segments, absolute paths and backslashes")
    fun relative() {
        val layout = ArtifactLayout(out, "paper-26.3-a")
        assertEquals("logs/harness.log", layout.relative(layout.harnessLogFile))
        assertEquals("tests/t/screenshots/Alice/s.png", layout.screenshot("t", "Alice", "s"))
        assertEquals("logs/sessions/1/clients/Alice.2.log", ArtifactLayout.sessionClientLog(1, "Alice", 2))
        assertThrows<IllegalArgumentException> { layout.relative(out.resolve("elsewhere")) }
        assertThrows<IllegalArgumentException> { ArtifactLayout.checkRelative("tests/../x") }
        assertThrows<IllegalArgumentException> { ArtifactLayout.checkRelative("/etc/passwd") }
        assertThrows<IllegalArgumentException> { ArtifactLayout.checkRelative("tests\\x") }
    }

    @Test
    @DisplayName("A marked run dir is wiped and an unmarked one with contract entries is refused")
    fun marker() {
        val layout = ArtifactLayout(out, "run")
        layout.prepare()
        layout.path("logs/old.log").also { it.parent.createDirectories() }.writeText("old")
        layout.prepare()
        assertFalse(layout.path("logs/old.log").exists())

        val foreign = ArtifactLayout(out, "foreign")
        foreign.runDir.resolve("logs").createDirectories()
        assertThrows<SetupException> { foreign.prepare() }
    }

    @Test
    @DisplayName("Stale marked run dirs are pruned and unmarked ones kept")
    fun prune() {
        ArtifactLayout(out, "keep").prepare()
        ArtifactLayout(out, "stale").prepare()
        out.resolve("user").createDirectories().resolve("file").createFile()
        assertEquals(listOf("stale"), ArtifactLayout.pruneStale(out, setOf("keep")))
        assertTrue(out.resolve("keep").exists())
        assertTrue(out.resolve("user").exists())
    }
}
