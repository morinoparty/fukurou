package party.morino.fukurou.engine.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginJarInspectorTest {
    @TempDir
    lateinit var dir: Path

    /** マジックナンバーと版だけを持つ、最小限のクラスファイルの先頭部分。 */
    private fun classBytes(major: Int) =
        byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, (major shr 8).toByte(), major.toByte()) +
            ByteArray(8)

    private fun jar(name: String, entries: Map<String, Any>): Path {
        val path = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(if (content is String) content.toByteArray() else content as ByteArray)
                zip.closeEntry()
            }
        }
        return path
    }

    @Test
    @DisplayName("Reads the descriptor and the class major, skipping META-INF/versions")
    fun readsDescriptorAndMajor() {
        val result = PluginJarInspector.inspect(
            jar(
                "Example.jar",
                mapOf(
                    "plugin.yml" to "name: Example\nversion: 1.10\nmain: dev.example.Example\n",
                    "dev/example/Example.class" to classBytes(65),
                    "dev/example/Util.class" to classBytes(61),
                    "META-INF/versions/25/dev/example/Util.class" to classBytes(69),
                ),
            ),
        )
        assertEquals(Triple("Example", "1.10", 65), Triple(result.name, result.version, result.classFileMajor))
        assertEquals(64, result.sha256.length)
    }

    @Test
    @DisplayName("paper-plugin.yml takes precedence over plugin.yml")
    fun paperPluginFirst() {
        val result = PluginJarInspector.inspect(
            jar("Both.jar", mapOf("plugin.yml" to "name: Legacy\nversion: '1'\n", "paper-plugin.yml" to "name: Modern\nversion: '2'\n")),
        )
        assertEquals(Triple("Modern", "2", null), Triple(result.name, result.version, result.classFileMajor))
    }

    @Test
    @DisplayName("A plain library has no descriptor and broken files are rejected")
    fun plainAndBroken() {
        val plain = PluginJarInspector.inspect(jar("lib.jar", mapOf("a/B.class" to classBytes(52))))
        assertNull(plain.name)
        assertEquals(52, plain.classFileMajor)
        val broken = dir.resolve("broken.jar").also { Files.writeString(it, "not a zip") }
        assertTrue(assertThrows<SetupException> { PluginJarInspector.inspect(broken) }.message!!.contains("not a readable jar"))
        val badYaml = jar("Bad.jar", mapOf("plugin.yml" to "name: [unclosed\nmain: a.B\n"))
        assertTrue(assertThrows<SetupException> { PluginJarInspector.inspect(badYaml) }.message!!.contains("invalid plugin descriptor"))
    }
}
