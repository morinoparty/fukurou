package party.morino.fukurou.engine.plugin

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.net.Downloader
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.plugin.SystemPropertySource
import java.nio.file.Path

class PluginResolverTest {
    @TempDir
    lateinit var dir: Path

    @Test
    @DisplayName("URL file names are decoded, sanitized and fall back to a hashed name")
    fun urlFileNames() {
        assertEquals("Vault_1.7.jar", PluginResolver.urlFileName("https://example.com/files/Vault%201.7.jar"))
        val query = PluginResolver.urlFileName("https://example.com/download?id=3")
        assertTrue(query.startsWith("dependency-") && query.endsWith(".jar"))
        assertEquals("dependency-${PluginResolver.shortHash("https://e.com/.jar")}.jar", PluginResolver.urlFileName("https://e.com/.jar"))
    }

    @Test
    @DisplayName("An unset system property plugin explains how to pass it")
    fun unsetSystemProperty() = runTest {
        val config = FukurouConfig.fromSources(mapOf("fukurou.workDir" to dir.toString()), emptyMap())
        val error = assertThrows<SetupException> {
            PluginResolver(config, Downloader()).resolve("under-test", SystemPropertySource("minestamp"))
        }
        assertTrue(error.message!!.startsWith("fukurou.plugin.minestamp is not set; pass -Pfukurou.plugin.minestamp=<jar>"))
    }
}
