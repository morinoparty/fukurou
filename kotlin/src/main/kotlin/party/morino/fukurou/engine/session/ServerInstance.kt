package party.morino.fukurou.engine.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.kyori.adventure.key.Key
import party.morino.fukurou.Fukurou
import party.morino.fukurou.engine.audience.BossBarSync
import party.morino.fukurou.engine.boot.JavaCheck
import party.morino.fukurou.engine.boot.ServerBoot
import party.morino.fukurou.engine.log.Ansi
import party.morino.fukurou.engine.log.LogWindow
import party.morino.fukurou.engine.log.WindowedLogView
import party.morino.fukurou.engine.net.Sha256
import party.morino.fukurou.engine.process.HostCheck
import party.morino.fukurou.engine.services.EnginePlatformServices
import party.morino.fukurou.engine.step.StepRunner
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import party.morino.fukurou.log.LogView
import party.morino.fukurou.player.Player
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.plugin.PluginSetBuilder
import party.morino.fukurou.result.ResultWriter
import party.morino.fukurou.result.RunObserver
import party.morino.fukurou.result.RunRecorder
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.kind.IsolationMode
import party.morino.fukurou.result.model.kind.PluginRole
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.run.RunFailure
import party.morino.fukurou.result.model.session.LogInfo
import party.morino.fukurou.result.model.session.LogKind
import party.morino.fukurou.result.model.step.StepPhase
import party.morino.fukurou.result.model.suite.ArenaInfo
import party.morino.fukurou.result.model.suite.PluginInfo
import party.morino.fukurou.result.model.suite.SelectionInfo
import party.morino.fukurou.result.model.suite.SuiteInfo
import party.morino.fukurou.result.model.test.LogRange
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.result.output.ArtifactLayout
import party.morino.fukurou.result.output.HarnessLog
import party.morino.fukurou.result.output.ResultIds
import party.morino.fukurou.result.output.StatusMapper
import party.morino.fukurou.server.CommandCheck
import party.morino.fukurou.server.CommandFailedError
import party.morino.fukurou.server.CommandResponse
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.server.ServerDefinition
import party.morino.fukurou.server.ServerType
import party.morino.fukurou.spi.Capabilities
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.CommandChannel
import party.morino.fukurou.spi.PlatformSession
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.spi.capability.PluginSupport
import party.morino.fukurou.spi.capability.ResetPlanner
import party.morino.fukurou.spi.capability.WorldCommands
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.spi.model.CommandRoute
import party.morino.fukurou.spi.model.ProvisionRequest
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import party.morino.fukurou.world.BlockPos
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 起動済みのサーバー（GameServer の実装、run/session.py:39-300 GameSession と suite_run.py の進行の移植）。
 *
 * 1 インスタンス = 1 run ディレクトリ = 1 つの result.json。fresh-server で作り直しても同じインスタンスのまま
 * セッション（sessions[]）を進める。プレイヤー（PlayerSession）もセッションをまたいで同じインスタンスを使う。
 *
 * JUnit 拡張（WP7）はテストごとに [prepareTest] → [beginTest] → [resetBeforeTest] → 本体 → [finishTest] を呼ぶ。
 * [test] は同じ順序を JUnit なしで行う。
 *
 * @property fukurou 所有者
 * @property definition 凍結した設定
 * @property resultId result の id（run ディレクトリ名）
 * @property layout run ディレクトリの配置
 * @property harness harness.log
 * @property recorder result.json の記録係
 */
internal class ServerInstance private constructor(
    val fukurou: Fukurou,
    val definition: ServerDefinition,
    override val resultId: String,
    val layout: ArtifactLayout,
    val harness: HarnessLog,
    val recorder: RunRecorder,
) : GameServer, PlatformSession, StepHost {
    /** サーバーの種類。 */
    override val type: ServerType get() = definition.type

    /** result id の末尾。 */
    override val label: String get() = definition.label

    /** 記録係（RunObserver の境界）。 */
    override val observer: RunObserver get() = recorder

    /** run ごとの作業ディレクトリ。 */
    val dirs: SessionDirs = SessionDirs(fukurou.config.workDir, resultId)

    /** 種類の戦略。1 サーバーにつき 1 つ。 */
    val platform: ServerPlatform = type.createPlatform(
        EnginePlatformServices(fukurou.config, fukurou.downloader, harness::info, harness::warn),
    )

    /** parallel のレーンを起動するスコープ（置き去りにできるよう呼び出し元とは切り離す）。 */
    override val harnessScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("fukurou-$resultId"))

    /** ボスバーの表示の同期。 */
    val bossBars: BossBarSync = BossBarSync(this)

    /** 今のセッションの index（start の前は -1）。 */
    @Volatile
    var sessionIndex: Int = -1
        private set

    /** 今のセッションのサーバーの起動と準備（プロセス・ログ・コマンド経路）。 */
    @Volatile
    private var boot: ServerBoot? = null

    /** 今のセッションの能力。 */
    @Volatile
    private var capabilities: Capabilities = Capabilities.EMPTY

    /** 今のセッションのテスト用のサーバーログの窓。 */
    @Volatile
    private var serverWindow: LogWindow? = null

    /** プレイヤー名 → PlayerSession（セッションをまたいで使い回す）。 */
    private val sessions = ConcurrentHashMap<String, PlayerSession>()

    /** 今のセッションで参加したプレイヤー（参加順）。 */
    private val joined = CopyOnWriteArrayList<PlayerSession>()

    /** 次に使う前に起動し直すプレイヤー → 理由。 */
    val dirty: MutableMap<String, String> = ConcurrentHashMap()

    /** 今のセッションで回収済みのクライアントログ（再起動の前の分）。セッションの終了時にまとめて載せる。 */
    private val collectedLogs = CopyOnWriteArrayList<LogInfo>()

    /** テストの開始時に mark したログ（artifact のパス → 窓）。 */
    @Volatile
    private var marked: List<Pair<String, LogWindow>> = emptyList()

    /** 解決済みのプラグイン（最初の start で 1 回だけ解決する）。 */
    @Volatile
    private var plugins: List<ResolvedPlugin>? = null

    /** このセッションで既にテストを走らせたか（fresh-server の判定）。 */
    @Volatile
    private var sessionRanTest = false

    /** サーバーの死亡や再起動の失敗で以後のテストを走らせない理由。 */
    @Volatile
    var deadReason: String? = null
        private set

    /** 実行中のテストでステップが見たサーバーの死亡。 */
    @Volatile
    private var serverError: ServerUnavailableException? = null

    /** sessionStarted を送り、まだ sessionFinished を送っていないセッションがあるか。 */
    @Volatile
    private var sessionOpen = false

    /** stop 済みか。 */
    @Volatile
    private var closed = false

    /** test { } の計画順。 */
    private var nextOrder = 0

    /** サーバーのコンソールの記録（テストごとの窓）。 */
    override val log: LogView = WindowedLogView(
        window = { serverWindow ?: throw IllegalStateException("$resultId has not been started") },
        source = "the server log ($resultId)",
        artifactPath = { ArtifactLayout.sessionServerLog(sessionIndex) },
        liveness = ::checkLiveness,
        deadline = { ActiveTests.on(this)?.deadline },
    )

    /** クライアントの接続先。 */
    override val joinAddress: InetSocketAddress
        get() = boot?.provisioned?.joinAddress ?: throw IllegalStateException("$resultId has not been started")

    /** 参加済みのプレイヤー（参加順）。 */
    override val players: List<Player> get() = joined.toList()

    /** 今のセッションで参加したプレイヤー（PlatformSession）。 */
    override val joinedPlayers: List<String> get() = joined.map { it.name }

    /** 今のセッションのコマンド経路（PlatformSession）。 */
    override val channel: CommandChannel? get() = boot?.channel

    /** 参加済みのプレイヤー（内部の型のまま）。 */
    val joinedSessions: List<PlayerSession> get() = joined.toList()

    /** 今のセッションのサーバーログのファイル。 */
    val serverLogFile: Path get() = layout.path(ArtifactLayout.sessionServerLog(sessionIndex))

    // --- 起動 --------------------------------------------------------------------

    /**
     * サーバーを起動し、準備完了（レディネス + プラグインの有効化）まで待つ（session.py:84 start + suite_run.py:231 _setup）。
     *
     * 失敗は run の失敗（setup / server-start）として記録してから投げる。呼び出し側は記録し直さなくてよい。
     */
    suspend fun start(kind: SessionKind) {
        check(!closed) { "$resultId was stopped" }
        var phase = RunFailurePhase.SETUP
        try {
            // Xvfb などが無いホストでは、ダウンロードより前に止める
            HostCheck.ensure(fukurou.config)
            if (!fukurou.config.acceptEula) throw SetupException(EULA_MESSAGE)
            // 能力と設定の整合は、ダウンロードや起動より前に確かめる（プラグインを入れられない種類など）
            validate(platform.bind(this))
            val resolved = resolvePlugins()
            phase = RunFailurePhase.SERVER_START
            beginSession(kind)
            val started = ServerBoot.boot(
                platform = platform,
                name = resultId,
                request = ProvisionRequest(
                    serverDir = dirs.serverDir,
                    sessionIndex = sessionIndex,
                    plugins = resolved,
                    serverFiles = serverFiles(),
                    // 参加者の数だけでは足りない利用（後から join する）もあるので、Paper の既定を下限にする
                    maxPlayers = maxOf(definition.players.size, MIN_MAX_PLAYERS),
                    serverHeap = definition.serverHeap,
                    serverJava = fukurou.config.serverJava,
                ),
                prepare = dirs::wipeServerDir,
                logFile = serverLogFile,
                artifactPath = ArtifactLayout.sessionServerLog(sessionIndex),
                timeout = definition.startTimeout,
                warn = harness::warn,
            )
            boot = started
            serverWindow = LogWindow(started.logFile)
            capabilities = platform.bind(this)
            checkPlugins(resolved)
            harness.info("session $sessionIndex (${kind.name.lowercase()}): $resultId is ready at ${started.provisioned.joinAddress}")
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = StatusMapper.messageOf(error)
            harness.error("${phase.name.lowercase()} failed: $message", error)
            recorder.runFailed(phase, message)
            deadReason = message
            // 起動の途中で止まったプロセスを残さず、ここまでのログを回収する
            withContext(NonCancellable) { runCatching { stopSession(message) } }
            throw error
        }
    }

    /** 宣言したプレイヤーのクライアントを先にインストールする（suite_run.py:277、全員の参加より前）。 */
    suspend fun install(profiles: List<PlayerProfile>) {
        profiles.forEach { playerSession(it).ensureInstalled() }
    }

    /** セッションの状態を新しくして sessionStarted を送る。 */
    private fun beginSession(kind: SessionKind) {
        sessionIndex++
        sessionRanTest = false
        joined.clear()
        dirty.clear()
        collectedLogs.clear()
        marked = emptyList()
        serverError = null
        sessions.values.forEach { it.launches = 0 }
        bossBars.reset()
        sessionOpen = true
        recorder.sessionStarted(sessionIndex, kind)
    }

    /** 能力と設定の整合を検査する（§1.3）。 */
    private fun validate(capabilities: Capabilities) {
        if (definition.plugins.isNotEmpty() && capabilities.get(PluginSupport::class) == null) {
            throw SetupException("${type.id} servers cannot load plugins (no PluginSupport capability); remove plugins { } from $label")
        }
        if (definition.isolation is Isolation.Reset && capabilities.get(ResetPlanner::class) == null) {
            throw SetupException(
                "${type.id} servers cannot reset between tests (no ResetPlanner capability); " +
                    "set isolation = Isolation.FreshServer or Isolation.None on $label",
            )
        }
    }

    /** プラグインを解決し、サーバーの Java が足りるかを確かめる（最初の start だけ）。 */
    private suspend fun resolvePlugins(): List<ResolvedPlugin> {
        plugins?.let { return it }
        val resolved = fukurou.pluginResolver.resolve(definition.plugins)
        resolved.forEach { harness.info("plugin ${it.file} (${it.role}${it.source?.let { source -> ", $source" }.orEmpty()})") }
        JavaCheck.check(fukurou.mojang, type.minecraftVersion, fukurou.config.serverJava, resolved, harness::warn)
        plugins = resolved
        return resolved
    }

    /** プラグインの有効化を確かめ、serverReady を送る。無効なプラグインがあれば SetupException。 */
    private suspend fun checkPlugins(resolved: List<ResolvedPlugin>) {
        val support = capabilities.get(PluginSupport::class)
        val enabled = if (support != null && resolved.isNotEmpty()) {
            support.checkEnabled(::readServerLog, resolved, PLUGIN_CHECK_TIMEOUT)
        } else {
            emptyMap()
        }
        val infos = resolved.map { pluginInfo(it, enabled[it.file]) }
        recorder.serverReady(platform.describe(boot!!.provisioned), infos)
        val failed = infos.filter { it.enabled == false }
        if (failed.isNotEmpty()) {
            // 詳しい理由（どのエラー行か）は種類の PluginSupport が harness.log に書いている
            throw SetupException(
                "plugin check failed: ${failed.joinToString(", ") { it.name ?: it.file }} " +
                    "${if (failed.size == 1) "was" else "were"} not enabled; see ${ArtifactLayout.HARNESS_LOG} and " +
                    ArtifactLayout.sessionServerLog(sessionIndex),
            )
        }
        harness.info("plugins enabled: ${infos.joinToString(", ") { it.name ?: it.file }.ifEmpty { "none" }}")
    }

    /** セッションの開始からのサーバーログ（ANSI 除去済み、PlatformSession）。 */
    override fun readServerLog(): String {
        val file = boot?.logFile ?: return ""
        if (!file.isRegularFile()) return ""
        return Ansi.strip(String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    /** result.plugins の 1 要素。 */
    private fun pluginInfo(plugin: ResolvedPlugin, enabled: Boolean?): PluginInfo =
        PluginInfo(
            file = plugin.file,
            sha256 = plugin.sha256,
            name = plugin.descriptorName,
            version = plugin.descriptorVersion,
            role = if (plugin.role == PluginSetBuilder.DEPENDENCY) PluginRole.DEPENDENCY else PluginRole.UNDER_TEST,
            source = plugin.source,
            classFileMajor = plugin.classFileMajor,
            enabled = enabled,
        )

    /**
     * 最初にコピーするディレクトリ。provision は 1 つしか受け取らないので、複数なら宣言順に重ねた 1 つにまとめる。
     */
    @OptIn(ExperimentalPathApi::class)
    private fun serverFiles(): Path? {
        val directories = definition.serverFiles
        if (directories.size <= 1) return directories.firstOrNull()
        val merged = dirs.root.resolve("server-files")
        if (Files.exists(merged)) merged.deleteRecursively()
        // 後に宣言したディレクトリのファイルが勝つ（上書きする）
        directories.forEach { source -> source.copyToRecursively(merged, followLinks = false, overwrite = true) }
        return merged
    }

    // --- 参加 --------------------------------------------------------------------

    /** プレイヤーの PlayerSession（無ければ作る）。 */
    suspend fun playerSession(profile: PlayerProfile): PlayerSession {
        sessions[profile.name]?.let { existing ->
            // 同じ名前で op の違う宣言は、どちらの権限で動かすか決まらない
            require(existing.profile == profile) { "${profile.name} was already declared as ${existing.profile}" }
            return existing
        }
        val portablemc = fukurou.portableMc()
        return sessions.computeIfAbsent(profile.name) { PlayerSession.create(this, profile, portablemc) }
    }

    /** プレイヤーを参加させる。失敗は run の失敗（client-join）として記録してから投げる。 */
    override suspend fun join(profile: PlayerProfile): Player {
        check(!closed) { "$resultId was stopped" }
        checkNotNull(boot) { "$resultId has not been started" }
        val player = playerSession(profile)
        if (player in joined) return player
        try {
            JoinCoordinator.join(this, player)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = StatusMapper.messageOf(error)
            harness.error("client-join failed: $message", error)
            recorder.runFailed(RunFailurePhase.CLIENT_JOIN, message)
            throw error
        }
        return player
    }

    /** 宣言順に 1 人ずつ参加させる（同時に起動すると CPU を取り合い、読み込みが大幅に遅くなるため）。 */
    override suspend fun join(vararg profiles: PlayerProfile): List<Player> = profiles.map { join(it) }

    /** JoinCoordinator から: 参加を確認した。窓を付け替えて記録する。 */
    fun playerJoined(player: PlayerSession) {
        if (player !in joined) joined += player
        recorder.playerJoined(player.name)
    }

    /** 参加済みのプレイヤー。 */
    override fun player(name: String): Player =
        joined.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException(
                "$name has not joined $resultId; joined players: ${joined.joinToString(", ") { it.name }.ifEmpty { "none" }}",
            )

    // --- コマンド -------------------------------------------------------------------

    /** 種類のコマンド経路へ送る。ステップとして記録する。 */
    override suspend fun command(command: String, check: CommandCheck): CommandResponse {
        val stripped = command.removePrefix("/")
        return StepRunner.step(this, StepRunner.ON_SERVER, ACTION_COMMAND, stripped) { _, _ ->
            val response = send(stripped)
            // エラーの応答でも例外にならない経路なので、黙って失敗すると以後のステップが違うワールドで走る
            if (check == CommandCheck.FailOnError && platform.responseCheck.isError(CommandCall(stripped), response.text)) {
                throw CommandFailedError(stripped, response.text, resultId)
            }
            response
        }
    }

    /** 能力の計画したコマンドを 1 つずつ送り、1 コマンド 1 ステップで記録する。 */
    suspend fun executeCalls(calls: List<CommandCall>) {
        for (call in calls) {
            StepRunner.step(this, StepRunner.ON_SERVER, ACTION_COMMAND, call.command) { _, _ ->
                // v1 はプロキシが無いので、バックエンドへの振り分けはできない
                if (call.route is CommandRoute.PlayerBackend) throw UnsupportedCapabilityException(type.id, "CommandRoute.PlayerBackend")
                val response = send(call.command)
                if (platform.responseCheck.isError(call, response.text)) throw CommandFailedError(call.command, response.text, resultId)
            }
        }
    }

    /**
     * 記録せずにコマンドを送る（リセットや能力の実行から）。
     *
     * @throws ServerUnavailableException サーバーが応答しない。プロセスが終わっていれば「is not running」
     */
    suspend fun send(command: String): CommandResponse {
        val current = channel ?: throw UnsupportedCapabilityException(type.id, "commands")
        return try {
            current.send(command)
        } catch (error: ServerUnavailableException) {
            // プロセスが終わっているなら、RCON の接続失敗ではなくサーバーの停止として伝える
            val exit = boot?.process?.exitCode ?: throw error
            throw ServerUnavailableException("$resultId is not running (exited with code $exit)")
        }
    }

    /** 1 ブロックを埋める。 */
    override suspend fun fill(from: BlockPos, to: BlockPos, block: String, world: Key) {
        executeCalls(worldCommands().fill(from, to, block, world))
    }

    /** 1 ブロックを置く。 */
    override suspend fun setBlock(at: BlockPos, block: String, world: Key) {
        executeCalls(worldCommands().setBlock(at, block, world))
    }

    /** 時刻を設定する。 */
    override suspend fun time(ticks: Long) {
        executeCalls(worldCommands().time(ticks))
    }

    /** 天気を晴れにする。 */
    override suspend fun weatherClear() {
        executeCalls(worldCommands().weatherClear())
    }

    /** WorldCommands の能力。 */
    private fun worldCommands(): WorldCommands = capability(WorldCommands::class) ?: throw UnsupportedCapabilityException(type.id, "WorldCommands")

    /** 種類が提供する能力。 */
    override fun <C : Capability> capability(kind: KClass<C>): C? = capabilities.get(kind)

    // --- ログ ------------------------------------------------------------------------

    /** サーバーログの現在の末尾。 */
    override fun mark(): LogMark = log.mark()

    /** サーバーログに pattern が出るまで待つ（wait_for_log ステップ）。 */
    override suspend fun awaitLog(pattern: Regex, timeout: Duration, after: LogMark?): LogMatch =
        StepRunner.step(this, StepRunner.ON_SERVER, "wait_for_log", pattern.pattern) { _, _ -> log.await(pattern, timeout, after) }

    /** サーバーログに pattern の行があれば LogAssertionError（assert_no_log ステップ）。 */
    override fun assertNoLog(pattern: Regex, after: LogMark?) {
        StepRunner.instant(this, StepRunner.ON_SERVER, "assert_no_log", pattern.pattern) { log.assertAbsent(pattern, after) }
    }

    // --- 生存 --------------------------------------------------------------------

    /** サーバーと参加中の全クライアントが生きているか（すべての待ちが呼ぶ）。 */
    fun checkLiveness() {
        checkServerAlive()
        checkClientsAlive()
    }

    /** サーバーのプロセスが生きているか。 */
    fun checkServerAlive() {
        val exit = boot?.process?.exitCode ?: return
        throw ServerUnavailableException("$resultId is not running (exited with code $exit)")
    }

    /** サーバーのプロセスが生きているか（例外にしない版）。 */
    val isServerAlive: Boolean get() = boot?.process?.isAlive == true

    /** 参加中のクライアントの生存。 */
    override fun checkClientsAlive() {
        joined.forEach { it.client.checkAlive() }
    }

    /** 参加中のクライアントのうち死んでいるもの。 */
    override fun deadClient(): ClientDiedException? =
        joined.firstNotNullOfOrNull { player -> runCatching { player.client.checkAlive() }.exceptionOrNull() as? ClientDiedException }

    /** ステップがサーバーの死亡を見た。 */
    override fun serverDied(error: ServerUnavailableException) {
        if (serverError == null) serverError = error
    }

    /** harness.log への情報。 */
    override fun log(message: String): Unit = harness.info(message)

    /** harness.log への警告。 */
    override fun warn(message: String): Unit = harness.warn(message)

    // --- テストの進行（JUnit 拡張と test { } が共有する） ----------------------------------

    /**
     * テストの前の準備（suite_run.py:320-335）: 必要なら fresh-server に作り直し、汚れた・死んだクライアントを起動し直す。
     *
     * 失敗は run の失敗として記録し、未実行のテストを skipped にしてから投げる。
     *
     * @param fresh このテストが新しいサーバーを要求するか（@FreshServer / Isolation.FreshServer）
     * @throws ServerUnavailableException サーバーが既に死んでいる（deadReason）
     */
    suspend fun prepareTest(fresh: Boolean) {
        deadReason?.let { throw ServerUnavailableException(it) }
        // 起動直後のセッション（まだテストを走らせていない）はそのままで新品なので作り直さない
        if (fresh && sessionRanTest) restartFresh()
        for (player in joined.toList()) {
            val reason = dirty[player.name] ?: player.deathReason() ?: continue
            harness.info("${player.name}: relaunching the client ($reason)")
            try {
                player.relaunch()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val reasonText = "client relaunch failed: ${player.name}"
                val message = "$reasonText: ${StatusMapper.messageOf(error)}"
                harness.error(message, error)
                recorder.runFailed(RunFailurePhase.CLIENT_JOIN, message)
                recorder.skipPending(reasonText)
                deadReason = reasonText
                throw FukurouException(message, error)
            }
        }
    }

    /**
     * 今のセッションを閉じ、まっさらなサーバーで次のセッションを始めて全員を再参加させる（session.py:143-156）。
     *
     * 止めた後（メモリ予算による退避の後）に呼んでもよい。
     */
    suspend fun restartFresh() {
        val rejoin = joined.map { it.profile }
        stopSession(null)
        start(SessionKind.FRESH_SERVER)
        rejoin.forEach { join(it) }
    }

    /**
     * テストを始める: TestRun を作って登録し、testStarted を送り、ログの開始位置を付ける。
     *
     * @param testId 計画済みのテストの id（RunRecorder.runPlanned / plan で登録してあること）
     * @param timeout テストの期限
     * @param owner 同時に走ってよいテストの持ち主（ActiveTests.begin）
     */
    fun beginTest(testId: String, timeout: Duration, owner: Any? = null): TestRun {
        val run = TestRun(this, testId, sessionIndex, timeout)
        ActiveTests.begin(run, owner ?: run)
        sessionRanTest = true
        harness.info("test $testId starting (session $sessionIndex)")
        recorder.testStarted(testId, sessionIndex, joined.map { it.profile })
        markLogs()
        return run
    }

    /**
     * 隔離の設定に従ってテストの前のリセットを行う。Reset 以外では何もしない。
     *
     * @throws FukurouException リセットのコマンドが失敗した（テストは error / reset）
     * @throws ServerUnavailableException サーバーが応答しない
     */
    suspend fun resetBeforeTest(run: TestRun) {
        val reset = definition.isolation as? Isolation.Reset ?: return
        val info = try {
            Resetter.reset(this, reset)
        } catch (error: ServerUnavailableException) {
            recorder.resetFinished(run.testId, ResetInfo(error = error.message))
            serverDied(error)
            throw error
        }
        recorder.resetFinished(run.testId, info)
        info.error?.let { error ->
            run.resetError = error
            throw FukurouException("reset failed: $error")
        }
    }

    /**
     * テストを閉じる（suite_run.py:418-431 _finish_test）: 失敗なら全員の画面を撮り、ログ範囲を記録して testFinished を送る。
     * サーバーが死んでいれば run の失敗（server）にし、未実行のテストを skipped にする。
     *
     * @param error テストが投げた例外（成功なら null）
     * @return 記録した結末
     */
    suspend fun finishTest(run: TestRun, error: Throwable?): TestOutcome = withContext(NonCancellable) {
        try {
            val died = deadClient()
            val outcome = if (error is CancellationException && error !is TimeoutCancellationException) {
                // 取り消された（中断された）テストは走り切れなかったので、契約どおり skipped にする（suite_run.py:352）
                TestOutcome(TestStatus.SKIPPED, skipReason = SKIP_INTERRUPTED)
            } else {
                StatusMapper.outcome(
                    error = error,
                    failingStep = run.stepOf(error),
                    leasePhase = run.phase,
                    clientDead = died != null,
                    resetError = run.resetError,
                )
            }
            if (outcome.status == TestStatus.FAILED || outcome.status == TestStatus.ERROR) {
                harness.error("test ${run.testId} ${outcome.status.name.lowercase()}: ${outcome.message}")
                FailureCapture.capture(this@ServerInstance, run, outcome.provisionalStepId ?: run.firstFailedStep?.provisionalId)
                // 置き去りにしたレーンが操作しているかもしれないプレイヤーを先に（理由を優先して残す）
                run.stranded.forEach { dirty.putIfAbsent(it, DIRTY_STRANDED_REASON) }
                run.touched.forEach { dirty.putIfAbsent(it, DIRTY_INPUT_REASON) }
            } else {
                harness.info("test ${run.testId} ${outcome.status.name.lowercase()}")
            }
            recorder.testFinished(run.testId, outcome, logRanges())
            outcome
        } finally {
            ActiveTests.finish(run)
            // サーバーの死亡は以後のテストを止める（suite_run.py:297-303）
            val reason = StatusMapper.serverDiedReason(run.testId)
            val died = serverError?.let { RunFailure(RunFailurePhase.SERVER, "$reason: ${it.message}") }
                ?: StatusMapper.runFailure(error, run.testId)
                ?: if (boot != null && !isServerAlive) RunFailure(RunFailurePhase.SERVER, "$reason: server exited during the test") else null
            if (died != null && deadReason == null) {
                harness.error(died.message)
                recorder.runFailed(died.phase, died.message)
                recorder.skipPending(reason)
                deadReason = reason
            }
        }
    }

    /** 実行中のテストが無いときに、JUnit 以外から計画に無いテストを足す。 */
    private fun planStandalone(id: String, name: String, tags: Set<String>) {
        val source = "standalone:$resultId#$id"
        recorder.plan(
            PlannedTest(
                className = "standalone",
                method = id,
                id = id,
                name = name,
                tags = tags.sorted(),
                order = nextOrder++,
                source = source,
                sha256 = Sha256.of(source),
                isolation = if (definition.isolation == Isolation.FreshServer) IsolationMode.FRESH_SERVER else IsolationMode.RESET,
                timeoutSeconds = definition.testTimeout.inWholeMilliseconds / MILLIS_PER_SECOND,
                players = joined.map { it.profile },
            ),
        )
    }

    /** JUnit を使わないときの記録スコープ。mark → relaunch → reset → block → finish → result.json 書き換え。 */
    override suspend fun <T> test(id: String, name: String, tags: Set<String>, block: suspend GameServer.() -> T): T {
        ResultIds.checkTestId(id)
        check(!closed) { "$resultId was stopped" }
        planStandalone(id, name, tags)
        deadReason?.let { reason ->
            // 死んだサーバーではテストを走らせない（理由付きの skipped にする）
            recorder.testSkipped(id, reason)
            throw ServerUnavailableException(reason)
        }
        prepareTest(fresh = definition.isolation == Isolation.FreshServer)
        val run = beginTest(id, definition.testTimeout)
        var failure: Throwable? = null
        try {
            resetBeforeTest(run)
            return withContext(StepScope(run, StepPhase.TEST)) { block() }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            finishTest(run, failure)
        }
    }

    /** ステップを phase=fixture / fixture=name で記録する。 */
    override suspend fun <T> fixture(name: String, block: suspend GameServer.() -> T): T {
        ResultIds.checkTestId(name)
        val scope = StepScope.current() ?: StepScope(ActiveTests.on(this))
        return withContext(scope.copy(phase = StepPhase.FIXTURE, fixture = name)) { block() }
    }

    /** サーバーログと参加中のクライアントログにテストの開始位置を付ける（session.py:212 mark_logs）。 */
    private fun markLogs() {
        val windows = mutableListOf<Pair<String, LogWindow>>()
        serverWindow?.let { windows += ArtifactLayout.sessionServerLog(sessionIndex) to it }
        joined.forEach { windows += it.clientLogPath to it.logWindow }
        windows.forEach { it.second.mark() }
        marked = windows
    }

    /** リセットの出力を照合の対象から外す（logRanges には残る）。 */
    fun skipMarked() {
        marked.forEach { it.second.skip() }
    }

    /** mark 以降に書かれた行の範囲。1 行も無いログは載せない。 */
    private fun logRanges(): Map<String, LogRange> =
        marked.mapNotNull { (path, window) -> window.lineRange()?.let { path to it } }.toMap()

    // --- 停止 --------------------------------------------------------------------

    /**
     * 今のセッションを閉じる: クライアント → Xvfb → サーバーの順に止め、ログを回収して sessionFinished を送る（session.py:248-274）。
     *
     * 最後まで進めてから最初の失敗を投げる。
     */
    suspend fun stopSession(failure: String?) = withContext(NonCancellable) {
        if (!sessionOpen) return@withContext
        sessionOpen = false
        val current = boot
        boot = null
        val errors = mutableListOf<Throwable>()
        // 参加を確認できなかったクライアントや、起動の途中で失敗したもの（ディスプレイだけ残った）も止める
        for (player in sessions.values) {
            runCatching { player.shutdown() }.onFailure { errors += it; harness.error("could not stop ${player.name}", it) }
        }
        if (current != null) {
            runCatching { current.stop(platform) }.onFailure { errors += it; harness.error("could not stop the server", it) }
        }
        val logs = runCatching { collectLogs() }.getOrElse {
            errors += it
            harness.error("could not collect logs", it)
            emptyList()
        }
        recorder.sessionFinished(sessionIndex, logs, failure ?: deadReason)
        errors.firstOrNull()?.let { throw it }
    }

    /** サーバーのコンソールの記録（既に artifact の中にある）と、起動したクライアントのログ・クラッシュレポート。 */
    private fun collectLogs(): List<LogInfo> {
        val infos = mutableListOf<LogInfo>()
        if (serverLogFile.isRegularFile()) {
            infos += LogInfo(
                LogKind.SERVER,
                ArtifactLayout.sessionServerLog(sessionIndex),
            )
        }
        infos += collectedLogs
        for (player in sessions.values.filter { it.launches > 0 }) {
            layout.collectClientLog(sessionIndex, player.name, player.launches, player.client.latestLog)?.let { infos += it }
            infos += layout.collectCrashReports(player.name, player.client.crashReportsDir)
        }
        return infos
    }

    /** クライアントの再起動の前に、その起動のログを回収しておく。 */
    fun collectClientLog(player: PlayerSession) {
        layout.collectClientLog(sessionIndex, player.name, player.launches, player.client.latestLog)?.let { collectedLogs += it }
    }

    /** 止めて result.json を確定する。冪等。 */
    override suspend fun stop() {
        if (closed) return
        closed = true
        try {
            stopSession(null)
        } catch (error: Throwable) {
            // 後片付けの失敗は元の失敗を隠さないよう teardown として残す
            recorder.runFailed(RunFailurePhase.TEARDOWN, "could not stop the session: ${StatusMapper.messageOf(error)}")
        } finally {
            recorder.runFinished()
            harness.info("${recorder.status.name.lowercase()}: wrote ${layout.resultFile}")
            harness.close()
            harnessScope.cancel()
            fukurou.unregister(this)
            if (!fukurou.config.keepWork) runCatching { dirs.delete() }
        }
    }

    /** runBlocking(Dispatchers.IO) { stop() } と同じ。 */
    override fun close() {
        runBlocking(Dispatchers.IO) { stop() }
    }

    /**
     * JVM の終了（Gradle の取り消し・SIGTERM）: 実行中のテストを skipped: interrupted にし、run の失敗を記録して書く（§5.8）。
     * プロセスは ProcessRegistry が止める。
     */
    fun interrupt() {
        if (closed) return
        closed = true
        val running = ActiveTests.on(this)
        if (running != null) {
            // 走り切れなかったテストは契約どおり skipped にし、途中の記録は残さない
            recorder.testSkipped(running.testId, SKIP_INTERRUPTED)
            ActiveTests.finish(running)
        }
        recorder.runFailed(RunFailurePhase.INTERRUPTED, "interrupted during ${running?.testId ?: "session $sessionIndex"}")
        recorder.runFinished()
        harness.close()
    }

    /** 表示用。 */
    override fun toString(): String = "ServerInstance($resultId)"

    /** 生成と定数。 */
    companion object {
        /** EULA に同意していないときのメッセージ（suite_run.py:45、Kotlin 版の渡し方に合わせる）。 */
        const val EULA_MESSAGE: String =
            "fukurou downloads and runs the Minecraft server and clients, which requires accepting the " +
                "Minecraft EULA (https://aka.ms/MinecraftEULA). Pass -Pfukurou.acceptEula=true (or set FUKUROU_ACCEPT_EULA=true) to accept it."

        /** プラグインの有効化を待つ時間（session.py:31）。起動完了の時点で有効化は終わっているはず。 */
        val PLUGIN_CHECK_TIMEOUT: Duration = 15.seconds

        /** dirty の理由: passed で終わらなかったテストで入力を受けた（session.py:34）。 */
        const val DIRTY_INPUT_REASON: String = "the previous test left keys pressed or a screen open"

        /** dirty の理由: 置き去りにしたレーンがまだ操作していた（session.py:36）。 */
        const val DIRTY_STRANDED_REASON: String = "a lane of the previous test was still driving the client after its timeout"

        /** 中断されたテストの skipReason（suite_run.py:59）。 */
        const val SKIP_INTERRUPTED: String = "interrupted"

        /** コマンドのステップの action。 */
        private const val ACTION_COMMAND = "command"

        /** max-players の下限（Paper の既定と同じ）。 */
        private const val MIN_MAX_PLAYERS = 20

        /** ミリ秒 → 秒。 */
        private const val MILLIS_PER_SECOND = 1000.0

        /** JVM 内で使った run id（-2, -3 … を付けて重ならないようにする）。 */
        private val takenIds = mutableSetOf<String>()

        /**
         * run ディレクトリを用意し、記録係を作って計画を書く（ダウンロードより前）。起動はしない。
         *
         * @param suite result.suite（JUnit 拡張の情報）。null なら定義から作る
         * @param planned 計画したテスト
         */
        fun open(fukurou: Fukurou, definition: ServerDefinition, suite: SuiteInfo?, planned: List<PlannedTest>): ServerInstance {
            val candidate = ResultIds.runId(definition.type.id, definition.type.minecraftVersion.id, definition.label)
            val runId = synchronized(takenIds) {
                ResultIds.uniqueRunId(candidate, takenIds).also { takenIds += it }
            }
            val layout = ArtifactLayout(fukurou.config.outDir, runId)
            layout.prepare()
            val harness = HarnessLog(layout.harnessLogFile)
            if (runId != candidate) harness.warn("result id $candidate is already used in this JVM; writing $runId")
            val recorder = RunRecorder(
                runId = runId,
                label = definition.label,
                minecraft = MinecraftInfo(version = definition.type.minecraftVersion.id, server = definition.type.id),
                fukurou = RunRecorder.loadFukurouInfo(),
                suite = suite ?: suiteInfo(definition, source = null, sha256 = null),
                selection = SelectionInfo(fukurou.config.selectionTests, fukurou.config.selectionTags),
                writer = ResultWriter(layout.resultFile),
                warn = harness::warn,
            )
            // ジョブが途中で消えても「計画はできたが走らなかった」と分かるよう、ここで最初の result.json を書く
            recorder.runPlanned(planned)
            return ServerInstance(fukurou, definition, runId, layout, harness, recorder).also(fukurou::register)
        }

        /**
         * result.suite（§6.3）。Isolation.None は契約の enum に無いので reset・settle 0・arena false で表す。
         */
        fun suiteInfo(definition: ServerDefinition, source: String?, sha256: String?): SuiteInfo =
            when (val isolation = definition.isolation) {
                is Isolation.Reset -> SuiteInfo(
                    source = source,
                    sha256 = sha256,
                    isolation = IsolationMode.RESET,
                    settle = isolation.settle.inWholeMilliseconds / MILLIS_PER_SECOND,
                    gamemode = isolation.gamemode.id,
                    arena = isolation.arena?.let { ArenaInfo.Area(it.size, it.height) } ?: ArenaInfo.Disabled,
                )
                Isolation.FreshServer -> SuiteInfo(source = source, sha256 = sha256, isolation = IsolationMode.FRESH_SERVER, arena = ArenaInfo.Disabled)
                Isolation.None -> SuiteInfo(source = source, sha256 = sha256, isolation = IsolationMode.RESET, settle = 0.0, arena = ArenaInfo.Disabled)
            }
    }
}
