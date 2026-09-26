package party.morino.fukurou.engine.paper

import party.morino.fukurou.engine.paper.capability.PaperAudienceCommands
import party.morino.fukurou.engine.paper.capability.PaperPlayerCommands
import party.morino.fukurou.engine.paper.capability.PaperPluginSupport
import party.morino.fukurou.engine.paper.capability.VanillaResetPlanner
import party.morino.fukurou.engine.paper.command.ComponentCodec
import party.morino.fukurou.engine.paper.command.ErrorResponse
import party.morino.fukurou.engine.paper.command.RconChannel
import party.morino.fukurou.engine.paper.command.VanillaCommands
import party.morino.fukurou.engine.plugin.JavaRequirement
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.spi.Capabilities
import party.morino.fukurou.spi.CommandChannel
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.PlatformSession
import party.morino.fukurou.spi.ResponseCheck
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.spi.model.ChannelEndpoint
import party.morino.fukurou.spi.model.PlatformInfo
import party.morino.fukurou.spi.model.ProvisionRequest
import party.morino.fukurou.spi.model.Provisioned
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Locale

/**
 * Paper の ServerPlatform（§4.4）。1 サーバーにつき 1 つ作られ、セッションごとに provision される。
 *
 * @property type Paper の種類の値
 * @property services エンジンのサービス
 * @property api Paper のダウンロード API（テストで差し替えられるようにする）
 */
internal class PaperPlatform(
    override val type: Paper,
    private val services: PlatformServices,
    private val api: PaperApi = PaperApi(),
) : ServerPlatform {
    /** Component の JSON。サーバーのバージョンで形が決まるので 1 つを使い回す。 */
    private val codec = ComponentCodec(type.version)

    /** プラグインの導入と確認。確認の失敗は harness.log に書く。 */
    private val pluginSupport = PaperPluginSupport(services::warn)

    /** 最初の provision で決めたビルド。セッションを作り直しても同じ jar を使う。 */
    @Volatile
    private var resolvedBuild: PaperBuild? = null

    /** サーバーの java の主バージョン（result.java.server）。 */
    @Volatile
    private var javaMajor: Int? = null

    override suspend fun provision(request: ProvisionRequest): Provisioned {
        val build = resolvedBuild ?: resolveBuild().also { resolvedBuild = it }
        val jar = downloadJar(build)
        // ポートと RCON のパスワードはセッションごとに新しくする（server/process.py:34-41）
        val port = services.freePort()
        val rconPort = services.freePort()
        val password = newPassword()
        val managed = managedProperties(port, rconPort, password, request.maxPlayers)
        val ignored = PaperDirectory.prepare(
            serverDir = request.serverDir,
            serverFiles = request.serverFiles,
            plugins = request.plugins,
            pluginSupport = pluginSupport,
            properties = type.properties,
            managed = managed,
        )
        ignored.forEach { services.warn("server property $it is managed by fukurou; the given value is ignored") }
        if (javaMajor == null) javaMajor = JavaRequirement.actualMajor(request.serverJava)
        // Paperclip が展開するライブラリや Mojang の jar の置き場。実行ごとに作り直さないようキャッシュに置く
        val bundlerDir = bundlerDir()
        val command = listOf(
            request.serverJava.toString(),
            "-Xmx${request.serverHeap}",
            "-DbundlerRepoDir=$bundlerDir",
            // 古いビルドを使うと更新を促すために起動を 20 秒止めるため、それを抑止する
            "-DIReallyKnowWhatIAmDoingISwear=true",
            "-jar",
            jar.toString(),
            "--nogui",
        )
        return Provisioned(
            command = command,
            workingDir = request.serverDir,
            environment = emptyMap(),
            joinAddress = InetSocketAddress(LOOPBACK, port),
            channelEndpoint = ChannelEndpoint.Rcon(rconPort, password),
            // 同じバージョンの Paperclip の展開は直列にする（違うバージョンは並行して起動できる）
            bundlerLock = bundlerDir,
        )
    }

    /** 起動完了のログ。この後 RCON が待ち受けを開始する。 */
    override val readyPattern: Regex = Regex("Done \\(")

    /** 起動完了のログの直後は RCON がまだ待ち受けていないことがあるので、list が通るまでエンジンが 1 秒ごとに再試行する。 */
    override suspend fun confirmReady(channel: CommandChannel?): Boolean {
        if (channel == null) return true
        return try {
            channel.send("list")
            true
        } catch (_: ServerUnavailableException) {
            false
        }
    }

    override val portConflictPattern: Regex = Regex("FAILED TO BIND TO PORT")

    override fun joinedPattern(player: String): Regex = VanillaCommands.joined(player)

    override fun openChannel(provisioned: Provisioned): CommandChannel? =
        when (val endpoint = provisioned.channelEndpoint) {
            is ChannelEndpoint.Rcon -> RconChannel(LOOPBACK, endpoint.port, endpoint.password)
            null -> null
        }

    override val responseCheck: ResponseCheck get() = ErrorResponse

    override fun bind(session: PlatformSession): Capabilities =
        Capabilities.of(PaperPlayerCommands, VanillaResetPlanner, PaperAudienceCommands(codec), pluginSupport)

    override suspend fun requestStop(channel: CommandChannel?) {
        try {
            channel?.send("stop")
        } catch (_: ServerUnavailableException) {
            // 既に止まっているか応答しないなら、エンジンがプロセスグループを止める
        }
    }

    override fun describe(provisioned: Provisioned): PlatformInfo {
        val build = resolvedBuild
        return PlatformInfo(
            serverKind = type.id,
            version = type.version.id,
            build = build?.id,
            channel = build?.channel?.lowercase(Locale.ROOT),
            javaMajor = javaMajor,
        )
    }

    /** ビルドを決める。決め打ちのビルドがしきい値より不安定なら、使うが警告する（suite_run.py:127）。 */
    private suspend fun resolveBuild(): PaperBuild {
        val build = api.resolve(type.version, type.channel, type.build)
        services.log("Paper ${type.version} build ${build.id} (${build.channel})")
        if (!PaperBuildPicker.channelAccepted(build.channel, type.channel)) {
            services.warn(
                "Paper ${type.version} build ${build.id} is ${build.channel}, less stable than " +
                    "fukurou.paperChannel=${type.channel.name.lowercase(Locale.ROOT)}; using it because the build was pinned",
            )
        }
        return build
    }

    /** jar を cache/paper/<v>/<build>/<name> に置く。Downloader がこのパスのキャッシュロックを持つ。 */
    private suspend fun downloadJar(build: PaperBuild): Path {
        val download = build.serverDownload()
        val destination = services.cacheDir.resolve("paper").resolve(type.version.id).resolve(build.id.toString())
            .resolve(download.name)
        return services.download(download.url, destination, download.checksums.sha256)
    }

    /** Paperclip の展開先（cache/paper-bundler/<v>）。 */
    private fun bundlerDir(): Path = services.cacheDir.resolve("paper-bundler").resolve(type.version.id)

    /** fukurou が制御に使うため、利用者に上書きさせない server.properties の値（server/process.py:43-52）。 */
    private fun managedProperties(port: Int, rconPort: Int, password: String, maxPlayers: Int): Map<String, String> =
        linkedMapOf(
            "server-ip" to LOOPBACK,
            "server-port" to port.toString(),
            "enable-rcon" to "true",
            "rcon.port" to rconPort.toString(),
            "rcon.password" to password,
            "max-players" to maxPlayers.toString(),
        )

    /** RCON はループバックでのみ待ち受けるが、念のため実行ごとに使い捨てのパスワードを使う（16 バイトの 16 進）。 */
    private fun newPassword(): String = HexFormat.of().formatHex(ByteArray(PASSWORD_BYTES).also(RANDOM::nextBytes))

    private companion object {
        /** サーバーと RCON の待ち受けアドレス。 */
        private const val LOOPBACK = "127.0.0.1"

        /** RCON のパスワードのバイト数。 */
        private const val PASSWORD_BYTES = 16

        /** パスワードの乱数。 */
        private val RANDOM = SecureRandom()
    }
}
