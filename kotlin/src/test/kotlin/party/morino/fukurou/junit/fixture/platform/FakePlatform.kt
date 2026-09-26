package party.morino.fukurou.junit.fixture.platform

import party.morino.fukurou.server.ServerType
import party.morino.fukurou.spi.Capabilities
import party.morino.fukurou.spi.CommandChannel
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.PlatformSession
import party.morino.fukurou.spi.ResponseCheck
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.spi.model.PlatformInfo
import party.morino.fukurou.spi.model.ProvisionRequest
import party.morino.fukurou.spi.model.Provisioned
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/** "Done (" を出して stop ファイルができるまで待つシェルを、サーバーの代わりに起動する。 */
class FakePlatform(override val type: ServerType, private val services: PlatformServices) : ServerPlatform {
    /** 今のセッションのサーバーディレクトリ（停止の要求で stop ファイルを置く）。 */
    @Volatile
    private var serverDir: Path? = null

    override suspend fun provision(request: ProvisionRequest): Provisioned {
        Files.createDirectories(request.serverDir)
        serverDir = request.serverDir
        return Provisioned(
            command = listOf("sh", "-c", "echo 'Done (0.1s)!'; while [ ! -e stop ]; do sleep 0.1; done"),
            workingDir = request.serverDir,
            environment = emptyMap(),
            joinAddress = InetSocketAddress("127.0.0.1", services.freePort()),
            channelEndpoint = null,
            bundlerLock = null,
        )
    }

    override val readyPattern: Regex = Regex("""Done \(""")

    override suspend fun confirmReady(channel: CommandChannel?): Boolean = true

    override fun joinedPattern(player: String): Regex = Regex("""\b$player joined""")

    override fun openChannel(provisioned: Provisioned): CommandChannel = FakeChannel()

    override val responseCheck: ResponseCheck = ResponseCheck { _, response -> response?.startsWith("Unknown") == true }

    override fun bind(session: PlatformSession): Capabilities = Capabilities.EMPTY

    override suspend fun requestStop(channel: CommandChannel?) {
        serverDir?.let { Files.createFile(it.resolve("stop")) }
    }

    override fun describe(provisioned: Provisioned): PlatformInfo = PlatformInfo("fake", type.minecraftVersion.id, null, null, 21)
}
