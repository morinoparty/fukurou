package party.morino.fukurou.engine.process

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** サーバーだけならクライアントの道具（Xvfb など）を求めない（v3）。 */
class HostCheckTest {
    @TempDir
    lateinit var bin: Path

    private val originalProbe = HostCheck.probe

    @AfterEach
    fun restore() {
        HostCheck.probe = originalProbe
    }

    /** 実行できる空のファイルを置く。 */
    private fun tool(name: String) {
        val file = Files.createFile(bin.resolve(name))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    @Test
    @DisplayName("A server-only host needs Linux, setsid and kill but not the X tools or x86_64")
    fun serverOnly() {
        tool("setsid")
        tool("kill")
        assertEquals(emptyList<String>(), HostCheck.problems("Linux", "aarch64", bin.toString(), clients = false))
        assertEquals(
            listOf(
                "the architecture is aarch64, not x86_64",
                "Xvfb is not on PATH (package xvfb)",
                "xdotool is not on PATH (package xdotool)",
                "xmodmap is not on PATH (package x11-xserver-utils)",
            ),
            HostCheck.problems("Linux", "aarch64", bin.toString(), clients = true),
        )
        assertEquals(listOf("the operating system is Mac OS X, not Linux"), HostCheck.problems("Mac OS X", "amd64", bin.toString(), clients = false))
    }

    @Test
    @DisplayName("A complete host has no problems for clients either")
    fun complete() {
        listOf("Xvfb", "xdotool", "xmodmap", "setsid", "kill").forEach(::tool)
        assertEquals(emptyList<String>(), HostCheck.problems("Linux", "amd64", bin.toString()))
    }

    @Test
    @DisplayName("ensure checks only the server tools and ensureClients checks the client tools")
    fun ensureSplit() {
        HostCheck.probe = { clients -> if (clients) "Xvfb is not on PATH (package xvfb)" else null }
        assertDoesNotThrow { HostCheck.ensure(FukurouConfig.fromSources(emptyMap(), emptyMap())) }
        assertNull(HostCheck.problemMessage(clients = false))
        val error = assertThrows<SetupException> { HostCheck.ensureClients() }
        assertEquals("Xvfb is not on PATH (package xvfb)", error.message)
    }
}
