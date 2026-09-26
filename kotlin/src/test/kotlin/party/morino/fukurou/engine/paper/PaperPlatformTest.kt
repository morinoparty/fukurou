package party.morino.fukurou.engine.paper

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.paper.command.RconChannel
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.spi.CommandChannel
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.PlatformSession
import party.morino.fukurou.spi.capability.AudienceCommands
import party.morino.fukurou.spi.capability.CommandEcho
import party.morino.fukurou.spi.capability.PlayerCommands
import party.morino.fukurou.spi.capability.PluginSupport
import party.morino.fukurou.spi.capability.ResetPlanner
import party.morino.fukurou.spi.capability.WorldCommands
import party.morino.fukurou.spi.model.ChannelEndpoint
import party.morino.fukurou.spi.model.Provisioned
import java.net.InetSocketAddress
import java.nio.file.Path

/** ネットワークを使わない配線の確認（WP6 が最初に触る部分）。 */
class PaperPlatformTest {
    private val services = object : PlatformServices {
        override val config: FukurouConfig = FukurouConfig.fromSources(emptyMap(), emptyMap())
        override val cacheDir: Path = Path.of("cache")
        override suspend fun download(url: String, destination: Path, sha256: String?, headers: Map<String, String>) = destination
        override suspend fun <T> withCacheLock(path: Path, block: suspend () -> T): T = block()
        override fun freePort(): Int = 0
        override fun log(message: String) {}
        override fun warn(message: String) {}
    }
    private val session = object : PlatformSession {
        override val channel: CommandChannel? = null
        override val joinedPlayers: List<String> = emptyList()
        override fun readServerLog(): String = ""
    }
    private val platform = PaperPlatform(Paper("26.3"), services)

    @Test
    @DisplayName("Binds every Paper capability")
    fun bind() {
        val capabilities = platform.bind(session)
        listOf(PlayerCommands::class, WorldCommands::class, CommandEcho::class, ResetPlanner::class, AudienceCommands::class, PluginSupport::class)
            .forEach { assertNotNull(capabilities.get(it), it.simpleName) }
    }

    @Test
    @DisplayName("Ready and join patterns, RCON channel and describe before provision")
    fun patternsAndChannel() {
        assertTrue(platform.readyPattern.containsMatchIn("[12:00:05 INFO]: Done (4.2s)! For help, type \"help\""))
        assertTrue(platform.joinedPattern("Alice").containsMatchIn("[12:00:06 INFO]: Alice joined the game"))
        assertTrue(runBlocking { platform.confirmReady(null) })
        val provisioned = Provisioned(listOf("java"), Path.of("."), emptyMap(), InetSocketAddress("127.0.0.1", 1), ChannelEndpoint.Rcon(1, "x"), null)
        assertTrue(platform.openChannel(provisioned) is RconChannel)
        assertEquals("paper", platform.describe(provisioned).serverKind)
    }
}
