package party.morino.fukurou

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.server.paper.PaperChannel
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

    @Test
    @DisplayName("Known keys map to FUKUROU_ UPPER_SNAKE environment variables")
    fun envNames() {
        assertEquals("FUKUROU_ACCEPT_EULA", FukurouConfig.envName("fukurou.acceptEula"))
        assertEquals("FUKUROU_MEMORY_BUDGET_MB", FukurouConfig.envName("fukurou.memoryBudgetMb"))
        assertEquals("FUKUROU_MINECRAFT_VERSION", FukurouConfig.envName("fukurou.minecraftVersion"))
        assertEquals("FUKUROU_SELECTION_TESTS", FukurouConfig.envName("fukurou.selection.tests"))
        assertEquals("FUKUROU_UPDATE_BASELINES", FukurouConfig.envName("fukurou.updateBaselines"))
        assertEquals(13, FukurouConfig.ENV_KEYS.size)
    }

    @Test
    @DisplayName("Every known key can come from the environment")
    fun everyKeyFromEnv() {
        val config = FukurouConfig.fromSources(
            emptyMap(),
            mapOf(
                "FUKUROU_ACCEPT_EULA" to "true",
                "FUKUROU_OUT_DIR" to "/out",
                "FUKUROU_SERVER_JAVA" to "current",
                "FUKUROU_MEMORY_BUDGET_MB" to "4096",
                "FUKUROU_MISSING_HOST" to "skip",
                "FUKUROU_KEEP_WORK" to "true",
                "FUKUROU_SELECTION_TESTS" to "a,b",
                "FUKUROU_SELECTION_TAGS" to "slow",
                "FUKUROU_UPDATE_BASELINES" to "true",
                "FUKUROU_UNKNOWN" to "x",
            ),
        )
        assertTrue(config.acceptEula)
        assertEquals(Path.of("/out"), config.outDir)
        assertEquals(ServerJava.Current, config.serverJava)
        assertEquals(4096L, config.memoryBudgetMb)
        assertEquals(MissingHostPolicy.SKIP, config.missingHost)
        assertTrue(config.keepWork)
        assertEquals(listOf("a", "b"), config.selectionTests)
        assertEquals(listOf("slow"), config.selectionTags)
        // 環境変数の値も properties に入る（他の読み手が同じ値を見る）
        assertEquals("true", config.properties["fukurou.updateBaselines"])
        assertEquals("4096", config.properties["fukurou.memoryBudgetMb"])
        // 既知でない FUKUROU_* は読まない
        assertFalse(config.properties.keys.any { it.contains("unknown", ignoreCase = true) })
    }

    @Test
    @DisplayName("Keys that used to be property-only follow property > env > .default")
    fun precedenceForFormerPropertyOnlyKeys() {
        val env = mapOf("FUKUROU_SERVER_JAVA" to "current", "FUKUROU_KEEP_WORK" to "true")
        val propertyWins = FukurouConfig.fromSources(mapOf("fukurou.serverJava" to "auto", "fukurou.keepWork" to "false"), env)
        assertEquals(ServerJava.Auto, propertyWins.serverJava)
        assertFalse(propertyWins.keepWork)
        assertEquals("auto", propertyWins.properties["fukurou.serverJava"])
        val envWins = FukurouConfig.fromSources(mapOf("fukurou.serverJava.default" to "/opt/java/bin/java"), env)
        assertEquals(ServerJava.Current, envWins.serverJava)
        assertTrue(envWins.keepWork)
        val defaultOnly = FukurouConfig.fromSources(mapOf("fukurou.serverJava.default" to "/opt/java/bin/java"), emptyMap())
        assertEquals(ServerJava.Path(Path.of("/opt/java/bin/java")), defaultOnly.serverJava)
        assertEquals("/opt/java/bin/java", defaultOnly.properties["fukurou.serverJava"])
    }

    @Test
    @DisplayName("An empty property is filled from the environment and seen by Paper.fromProperties")
    fun envFeedsPlatformFactories() {
        val config = FukurouConfig.fromSources(
            mapOf("fukurou.minecraftVersion" to ""),
            mapOf("FUKUROU_MINECRAFT_VERSION" to "1.21.11", "FUKUROU_PAPER_CHANNEL" to "beta", "FUKUROU_PAPER_BUILD" to "42"),
        )
        assertEquals("1.21.11", config.properties["fukurou.minecraftVersion"])
        val paper = Paper.fromProperties(config)
        assertEquals("1.21.11", paper.version.id)
        assertEquals(PaperChannel.Beta, paper.channel)
        assertEquals(42, paper.build)
    }

    @Test
    @DisplayName("serverJava parses auto, current and paths")
    fun serverJava() {
        assertEquals(ServerJava.Auto, FukurouConfig.fromSources(emptyMap(), emptyMap()).serverJava)
        assertEquals(ServerJava.Auto, FukurouConfig.fromSources(mapOf("fukurou.serverJava" to "AUTO"), emptyMap()).serverJava)
        assertEquals(ServerJava.Current, FukurouConfig.fromSources(mapOf("fukurou.serverJava" to "current"), emptyMap()).serverJava)
        assertEquals(
            ServerJava.Path(Path.of("/usr/lib/jvm/21/bin/java")),
            FukurouConfig.fromSources(mapOf("fukurou.serverJava" to "/usr/lib/jvm/21/bin/java"), emptyMap()).serverJava,
        )
        assertEquals(ServerJava.Path(Path.of("java21")), FukurouConfig.fromSources(mapOf("fukurou.serverJava" to "java21"), emptyMap()).serverJava)
    }

    @Test
    @DisplayName("Invalid values from the environment are setup errors")
    fun invalidEnvValues() {
        assertThrows<SetupException> { FukurouConfig.fromSources(emptyMap(), mapOf("FUKUROU_KEEP_WORK" to "yes")) }
        assertThrows<SetupException> { FukurouConfig.fromSources(emptyMap(), mapOf("FUKUROU_MEMORY_BUDGET_MB" to "lots")) }
    }
}
