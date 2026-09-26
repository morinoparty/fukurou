package party.morino.fukurou.engine.paper.capability

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.nio.file.Path
import kotlin.time.Duration

/** tests/test_server.py のプラグイン確認のケース。 */
class PluginLogStateTest {
    private val paperLog = """
        [12:00:01 INFO]: [Example] Loading server plugin Example v1.0
        [12:00:02 INFO]: [Example] Enabling Example v1.0
        [12:00:02 INFO]: [My_Plugin] Enabling My_Plugin v2
        [12:00:05 INFO]: Done (4.2s)! For help, type "help"
    """.trimIndent() + "\n"

    private fun identities(vararg names: String) = names.map { PluginIdentity(it, "${it.replace(" ", "")}.jar") }

    @Test
    @DisplayName("Passes when every plugin is enabled, and honors the log prefix")
    fun enabled() {
        val state = PluginLogState.read(paperLog, identities("Example", "My Plugin"))
        assertEquals(mapOf("Example.jar" to true, "MyPlugin.jar" to true), state.enabledByFile())
        assertNull(state.failureMessage())
        val prefixed = PluginIdentity("PrefixedPlugin", "Prefixed.jar", logPrefix = "PFX")
        assertTrue(PluginLogState.read("[16:18:04 INFO]: [PFX] Enabling PrefixedPlugin v1.0\n", listOf(prefixed)).enabled.single())
    }

    @Test
    @DisplayName("Reports error lines and plugins that were never enabled")
    fun errorsAndMissing() {
        val log = paperLog + "[12:00:03 ERROR]: Error occurred while enabling Example v1.0 (Is it up to date?)\n"
        val state = PluginLogState.read(log, identities("Example", "Other"))
        assertEquals(listOf(false, false), state.enabled)
        val message = checkNotNull(state.failureMessage())
        assertTrue(message.startsWith("plugin check failed: ") && "Error occurred while enabling Example" in message, message)
        assertTrue("Other was not enabled" in message, message)
    }

    @Test
    @DisplayName("Ties errors only to the plugin they are about (Core vs ExampleCore)")
    fun ownsError() {
        val log = """
            [12:00:01 INFO]: [Example] Enabling Example v1.0
            [12:00:01 INFO]: [Core] Enabling Core v1
            [12:00:02 ERROR]: Could not load plugin 'ExampleCore.jar' in folder 'plugins'
            [12:00:02 ERROR]: Error occurred while enabling Addon v1 (Is it up to date?) Example missing
        """.trimIndent()
        val state = PluginLogState.read(log, identities("Example", "Core", "ExampleCore", "Addon"))
        assertEquals(listOf(true, true, false, false), state.enabled)
        assertFalse("was not enabled" in checkNotNull(state.failureMessage()))
    }

    @Test
    @DisplayName("checkEnabled returns the map by file name and warns the failure")
    fun checkEnabled() = runTest {
        val warnings = mutableListOf<String>()
        val plugin = ResolvedPlugin(Path.of("Other.jar"), "Other.jar", "0", "under-test", null, "Other", "1", null, 65)
        val result = PaperPluginSupport(warnings::add).checkEnabled({ paperLog }, listOf(plugin), Duration.ZERO)
        assertEquals(mapOf("Other.jar" to false), result)
        assertTrue(warnings.single().contains("Other was not enabled"))
    }
}
