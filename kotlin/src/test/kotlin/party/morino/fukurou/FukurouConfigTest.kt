package party.morino.fukurou

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.SetupException
import java.nio.file.Path

class FukurouConfigTest {
    @Test
    @DisplayName("Property wins over env, env over .default, .default over fallback")
    fun precedence() {
        val both = FukurouConfig.fromSources(
            mapOf("fukurou.workDir" to "/p", "fukurou.workDir.default" to "/d"),
            mapOf("FUKUROU_WORK_DIR" to "/e"),
        )
        assertEquals(Path.of("/p"), both.workDir)
        val envOnly = FukurouConfig.fromSources(mapOf("fukurou.workDir.default" to "/d"), mapOf("FUKUROU_WORK_DIR" to "/e"))
        assertEquals(Path.of("/e"), envOnly.workDir)
        val defaultOnly = FukurouConfig.fromSources(mapOf("fukurou.workDir.default" to "/d"), emptyMap())
        assertEquals(Path.of("/d"), defaultOnly.workDir)
        val none = FukurouConfig.fromSources(emptyMap(), emptyMap())
        assertEquals(Path.of(".fukurou-work").toAbsolutePath().normalize(), none.workDir)
        assertTrue(none.workDir.isAbsolute)
        assertEquals(Path.of("fukurou-out").toAbsolutePath().normalize(), none.outDir)
        assertFalse(none.acceptEula)
        assertEquals(MissingHostPolicy.FAIL, none.missingHost)
        assertNull(none.memoryBudgetMb)
    }

    @Test
    @DisplayName("Plugins, selections and fukurou.* properties are collected")
    fun collections() {
        val config = FukurouConfig.fromSources(
            mapOf(
                "fukurou.plugin.minestamp" to "build/libs/a.jar",
                "fukurou.selection.tests" to "a, b,,",
                "fukurou.acceptEula" to "TRUE",
                "java.version" to "25",
            ),
            emptyMap(),
        )
        assertEquals(mapOf("minestamp" to Path.of("build/libs/a.jar").toAbsolutePath()), config.plugins)
        assertEquals(listOf("a", "b"), config.selectionTests)
        assertTrue(config.acceptEula)
        assertFalse("java.version" in config.properties)
    }

    @Test
    @DisplayName("GitHub token is masked in toString")
    fun masksToken() {
        val config = FukurouConfig.fromSources(emptyMap(), mapOf("GITHUB_TOKEN" to "ghp_secret"))
        assertEquals("ghp_secret", config.githubToken)
        assertFalse("ghp_secret" in config.toString())
    }

    @Test
    @DisplayName("Invalid values are setup errors")
    fun invalidValues() {
        assertThrows<SetupException> { FukurouConfig.fromSources(mapOf("fukurou.acceptEula" to "yes"), emptyMap()) }
        assertThrows<SetupException> { FukurouConfig.fromSources(mapOf("fukurou.missingHost" to "ignore"), emptyMap()) }
        assertThrows<SetupException> { FukurouConfig.fromSources(mapOf("fukurou.memoryBudgetMb" to "-1"), emptyMap()) }
    }
}
