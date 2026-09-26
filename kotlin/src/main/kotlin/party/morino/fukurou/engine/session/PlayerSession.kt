package party.morino.fukurou.engine.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.chat.ChatType
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.dialog.DialogLike
import net.kyori.adventure.identity.Identity
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.inventory.BookLike
import net.kyori.adventure.key.Key
import net.kyori.adventure.pointer.Pointers
import net.kyori.adventure.resource.ResourcePackInfoLike
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.resource.ResourcePackRequestLike
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import net.kyori.adventure.title.TitlePart
import party.morino.fukurou.engine.audience.AudienceDispatcher
import party.morino.fukurou.engine.audience.OfflineUuid
import party.morino.fukurou.engine.audience.PlayerPointers
import party.morino.fukurou.engine.client.ClientProcess
import party.morino.fukurou.engine.log.LogWindow
import party.morino.fukurou.engine.log.WindowedLogView
import party.morino.fukurou.engine.step.StepRunner
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.engine.x11.KeycodeResolver
import party.morino.fukurou.engine.x11.MinecraftWindow
import party.morino.fukurou.engine.x11.VirtualDisplay
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.error.InputException
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import party.morino.fukurou.log.LogView
import party.morino.fukurou.player.Chord
import party.morino.fukurou.player.KeySym
import party.morino.fukurou.player.Perspective
import party.morino.fukurou.player.Player
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.player.Screenshot
import party.morino.fukurou.result.model.test.ScreenshotInfo
import party.morino.fukurou.result.output.ArtifactLayout
import party.morino.fukurou.result.output.ResultIds
import party.morino.fukurou.spi.capability.AudienceCommands
import party.morino.fukurou.spi.capability.CommandEcho
import party.morino.fukurou.spi.capability.PlayerCommands
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 参加済みのプレイヤー（Player の実装、runner/player_session.py の移植）。
 *
 * 専用の仮想ディスプレイとクライアントを持つ。クライアントを起動し直しても同じインスタンスのまま中身を付け替えるので、
 * テストが持っている参照は古くならない。入力（キー・文字・チャット・撮影）はプレイヤーごとの Mutex で直列にし、
 * 2 つのレーンからの打鍵が混ざらないようにする。
 *
 * @property server 参加したサーバー
 * @property profile 参加前の宣言
 * @property client クライアントのプロセス
 * @property display 専用の仮想ディスプレイ
 */
internal class PlayerSession(
    override val server: ServerInstance,
    override val profile: PlayerProfile,
    val client: ClientProcess,
    val display: VirtualDisplay,
) : Player {
    /** プレイヤー名。 */
    override val name: String get() = profile.name

    /** オフラインモードの UUID。 */
    override val uuid: UUID = OfflineUuid.of(profile.name)

    /** Adventure の pointers。 */
    private val pointers: Pointers = PlayerPointers.of(profile.name, uuid)

    /** 入力を直列にするロック。 */
    private val input = Mutex()

    /** インストール（portablemc --dry）を 1 回にするロック。 */
    private val installLock = Mutex()

    /** インストール済みか。 */
    @Volatile
    private var installed = false

    /** ハーネスが送った F5 の回数。 */
    val perspective: PerspectiveCounter = PerspectiveCounter()

    /** クライアントのウィンドウ。キー入力が必要になった時点で探す（起動し直すと探し直す）。 */
    @Volatile
    private var window: MinecraftWindow? = null

    /** 今のセッションでの起動回数（ログの回収名の k）。 */
    @Volatile
    var launches: Int = 0

    /** クライアントを起動中か。 */
    @Volatile
    var running: Boolean = false
        private set

    /** 今の起動の latest.log の窓。起動し直すと付け替える。 */
    @Volatile
    var logWindow: LogWindow = LogWindow(client.latestLog)
        private set

    /** 今の起動の latest.log の artifact 内のパス（logRanges のキー）。 */
    @Volatile
    var clientLogPath: String = ArtifactLayout.sessionClientLog(0, profile.name, 1)
        private set

    /** クライアントの latest.log（テストごとの窓）。 */
    override val log: LogView = WindowedLogView(
        window = { logWindow },
        source = "${profile.name}'s client log",
        artifactPath = { clientLogPath },
        liveness = server::checkLiveness,
        // tearDown など記録しないスコープではテストの期限を使わない
        deadline = { StepRunner.deadlineOf(server) },
    )

    // --- 起動と停止 -----------------------------------------------------------------

    /** クライアント本体とアセットをダウンロードしておく（起動はしない）。2 回目以降は何もしない。 */
    suspend fun ensureInstalled() {
        installLock.withLock {
            if (installed) return
            server.log("$name: installing the client")
            client.install(server.definition.installTimeout)
            installed = true
        }
    }

    /** 専用ディスプレイを起動し、その上でクライアントを起動してサーバーへ参加させる（player_session.py:70 start）。 */
    suspend fun launch(address: InetSocketAddress) {
        // 前の起動のディスプレイ番号が再利用されうるので、キーマップを読み直させる
        val displayName = display.start()
        KEYCODES.invalidate(displayName)
        server.log("$name: display $displayName")
        client.start(displayName, address)
        launches++
        running = true
        window = null
        perspective.reset()
    }

    /** JoinCoordinator から: 参加を確認した。この起動の latest.log を窓にする。 */
    fun joined() {
        clientLogPath = ArtifactLayout.sessionClientLog(server.sessionIndex, name, launches)
        logWindow = LogWindow(client.latestLog)
    }

    /** クライアントとディスプレイを止める（player_session.py:132 stop）。 */
    suspend fun shutdown() {
        if (!running && !client.isAlive && !display.isAlive) return
        val displayName = runCatching { display.display }.getOrNull()
        try {
            client.stop(CLIENT_GRACE)
        } finally {
            display.stop(DISPLAY_GRACE)
            displayName?.let(KEYCODES::invalidate)
            window = null
            running = false
        }
    }

    /** 起動し直すべき理由（クライアントの死亡）。生きていれば null。 */
    fun deathReason(): String? = (runCatching { client.checkAlive() }.exceptionOrNull() as? ClientDiedException)?.message

    /**
     * クライアントを止めてログを回収し、起動し直して参加を待つ（session.py:168-178 relaunch）。
     */
    suspend fun relaunch() {
        shutdown()
        server.collectClientLog(this)
        server.dirty.remove(name)
        JoinCoordinator.join(server, this)
    }

    /** ウィンドウ。最初の呼び出し（と起動し直した後）は現れるまで待つ（player_session.py:123）。 */
    private suspend fun window(timeout: Duration = server.definition.joinTimeout): MinecraftWindow =
        window ?: MinecraftWindow.waitFor(display.display, KEYCODES, timeout).also { window = it }

    // --- 入力（xdotool） -------------------------------------------------------------

    /** 入力のステップ。記録し、プレイヤーの入力のロックを取ってから本体を実行する。 */
    private suspend fun <T> inputStep(action: String, label: String, body: suspend (TestRun?) -> T): T =
        StepRunner.step(server, name, action, label, inputPlayer = name) { run, _ -> input.withLock { body(run) } }

    /** キーを 1 回押す（press_key）。 */
    override suspend fun pressKey(key: KeySym) {
        inputStep(ACTION_PRESS_KEY, key.keysym) { run ->
            // passed で終わらなければ画面が開いたままかもしれない
            run?.touched?.add(name)
            window().pressKey(key)
            if (key == KeySym.F5) perspective.pressed()
        }
    }

    /** キーを同時に押す（press_key、label は "F3+d"）。 */
    override suspend fun pressChord(chord: Chord) {
        inputStep(ACTION_PRESS_KEY, chord.toString()) { run ->
            run?.touched?.add(name)
            window().pressChord(chord)
        }
    }

    /** 文字列を入力する（type_text）。 */
    override suspend fun typeText(text: String) {
        inputStep("type_text", text) { run ->
            run?.touched?.add(name)
            window().typeText(text)
        }
    }

    /** T でチャット欄を開き、0.5 秒待って入力し、Return で送る（chat）。 */
    override suspend fun chat(text: String) {
        inputStep("chat", text) { run ->
            // 最後まで送れれば画面は残らない。途中で失敗するとチャット欄が開いたままなので、その間だけ touched にする
            val alreadyTouched = run?.touched?.contains(name) ?: true
            run?.touched?.add(name)
            val current = window()
            current.pressKey(KeySym.T)
            // チャット欄が開く前に入力すると先頭の文字が欠ける
            delay(CHAT_OPEN_WAIT)
            current.typeText(text)
            current.pressKey(KeySym.ENTER)
            if (!alreadyTouched) run?.touched?.remove(name)
        }
    }

    /** チャット欄からコマンドを送り、種類の CommandEcho が返すサーバーログの行を待つ。 */
    override suspend fun sendCommand(command: String, timeout: Duration): LogMatch {
        val stripped = command.removePrefix("/")
        val echo = server.capability(CommandEcho::class) ?: throw UnsupportedCapabilityException(server.type.id, "CommandEcho")
        // 送信前に印を付け、同じテストの前のコマンドの行に一致しないようにする
        val mark = server.mark()
        chat("/$stripped")
        return server.awaitLog(echo.issuedCommand(name, stripped), timeout, mark)
    }

    /** F5 を必要な回数押して視点を合わせる（1 回 1 ステップ）。 */
    override suspend fun perspective(perspective: Perspective) {
        repeat(this.perspective.pressesTo(perspective)) { pressKey(KeySym.F5) }
    }

    /**
     * テストの前にチャットの HUD を消し（F3+D）、視点を一人称へ戻す（player_session.py:95-107）。ベストエフォートで記録しない。
     */
    suspend fun normalizeView() {
        try {
            input.withLock {
                val current = window()
                current.pressChord(KeySym.F3 + KeySym.D)
                repeat(perspective.pressesToFirstPerson()) { current.pressKey(KeySym.F5) }
            }
        } catch (error: FukurouException) {
            // 画面を開いたまま押した F5 は数え損なうので確実ではない。失敗しても警告に留める
            server.warn("$name: could not normalize the view: ${error.message}")
        }
        perspective.reset()
    }

    // --- スクリーンショット -------------------------------------------------------------

    /** F2 で撮影し、tests/<id>/screenshots/<player>/<name>.png に保存する（screenshot）。 */
    override suspend fun screenshot(name: String): Screenshot {
        // ファイル名とビューアのパスになるので、テストの id と同じ規則に限る
        require(ResultIds.TEST_ID.matches(name)) { "screenshot name '$name' must match ${ResultIds.TEST_ID.pattern}" }
        require(name != FAILURE_SCREENSHOT) { "'$FAILURE_SCREENSHOT' is reserved for the screenshot taken when a test fails" }
        return StepRunner.step(server, this.name, "screenshot", name, inputPlayer = this.name, screenshotOf = { it.artifactPath }) { run, stepId ->
            checkNotNull(run) { "screenshot($name) needs a running test; call it inside server.test { } or a JUnit test" }
            run.claimScreenshot(this.name, name, checkNotNull(stepId))
            input.withLock { capture(run, name, stepId, server.definition.joinTimeout) }
        }
    }

    /**
     * 失敗時の撮影（suite_run.py:562-584）。ウィンドウは短い時間（10 秒）だけ待ち、ステップにはしない。
     *
     * @param stepId 失敗したステップの仮 id
     */
    suspend fun captureFailure(run: TestRun, stepId: Long?, windowTimeout: Duration) {
        input.withLock { capture(run, FAILURE_SCREENSHOT, stepId, windowTimeout) }
    }

    /** 撮影して保存し、screenshotTaken を送る。呼び出し側が入力のロックを持っている前提。 */
    private suspend fun capture(run: TestRun, shotName: String, stepId: Long?, windowTimeout: Duration): Screenshot {
        val directory = client.screenshotsDir
        val before = pngs(directory)
        window(windowTimeout).pressKey(KeySym.F2)
        val png = waitForNewPng(directory, before)
        val relative = server.layout.screenshot(run.testId, name, shotName)
        val destination = server.layout.path(relative)
        val (width, height) = runInterruptible(Dispatchers.IO) {
            Files.createDirectories(destination.parent)
            Files.copy(png, destination, StandardCopyOption.REPLACE_EXISTING)
            PngHeader.size(destination)
        }
        server.log("$name: saved $relative (${width}x$height)")
        server.observer.screenshotTaken(run.testId, ScreenshotInfo(name, shotName, relative, width, height), stepId)
        return Screenshot(name, shotName, destination, relative, width, height)
    }

    /** 新しい PNG が現れ、書き込みが終わる（サイズが変わらなくなる）まで待つ（player_session.py:139-151）。 */
    private suspend fun waitForNewPng(directory: Path, before: Set<Path>): Path {
        val deadline = TimeSource.Monotonic.markNow() + SCREENSHOT_TIMEOUT
        while (deadline.hasNotPassedNow()) {
            val candidate = (pngs(directory) - before).maxOrNull()
            if (candidate != null) {
                val size = runInterruptible(Dispatchers.IO) { candidate.fileSize() }
                delay(SCREENSHOT_STABLE)
                val stable = runInterruptible(Dispatchers.IO) {
                    size > 0 && candidate.fileSize() == size && PngHeader.isPng(PngHeader.readHeader(candidate))
                }
                if (stable) return candidate
            }
            delay(SCREENSHOT_POLL)
        }
        throw InputException("$name: no new screenshot was written after pressing F2")
    }

    /** ディレクトリの PNG。 */
    private suspend fun pngs(directory: Path): Set<Path> = runInterruptible(Dispatchers.IO) {
        if (directory.isDirectory()) directory.listDirectoryEntries("*.png").toSet() else emptySet()
    }

    // --- サーバー側の操作 -------------------------------------------------------------

    /** PlayerCommands の能力。 */
    private fun commands(): PlayerCommands =
        server.capability(PlayerCommands::class) ?: throw UnsupportedCapabilityException(server.type.id, "PlayerCommands")

    /** 指定の位置へテレポートする。 */
    override suspend fun teleport(location: Location) {
        server.executeCalls(commands().teleport(name, location))
    }

    /** 座標を指定してテレポートする。 */
    override suspend fun teleport(x: Double, y: Double, z: Double, yaw: Float?, pitch: Float?, world: Key) {
        teleport(Location(x, y, z, yaw, pitch, world))
    }

    /** ゲームモードを変える。 */
    override suspend fun gamemode(mode: GameMode) {
        server.executeCalls(commands().gamemode(name, mode))
    }

    /** op にする。 */
    override suspend fun op() {
        server.executeCalls(commands().op(name))
    }

    /** op を外す。 */
    override suspend fun deop() {
        server.executeCalls(commands().deop(name))
    }

    /** アイテムを与える。 */
    override suspend fun give(item: Key, count: Int) {
        server.executeCalls(commands().give(name, item, count))
    }

    // --- クライアントログの照合 -----------------------------------------------------------

    /** クライアントログの現在の末尾。 */
    override fun mark(): LogMark = log.mark()

    /** \[CHAT\] の行で pattern に一致するものが出るまで待つ（wait_for_log）。 */
    override suspend fun awaitChat(pattern: Regex, timeout: Duration, after: LogMark?): LogMatch {
        val full = chatPattern(pattern)
        return StepRunner.step(server, name, "wait_for_log", full.pattern) { _, _ -> log.await(full, timeout, after) }
    }

    /** Component をプレーンテキストにして \[CHAT\] の行と照合する。 */
    override suspend fun awaitChat(message: Component, timeout: Duration, after: LogMark?): LogMatch = awaitChat(literal(message), timeout, after)

    /** \[CHAT\] の行で pattern に一致するものがあれば LogAssertionError（assert_no_log）。 */
    override fun assertNoChat(pattern: Regex, after: LogMark?) {
        val full = chatPattern(pattern)
        StepRunner.instant(server, name, "assert_no_log", full.pattern) { log.assertAbsent(full, after) }
    }

    /** Component をプレーンテキストにしたチャットの行があれば LogAssertionError。 */
    override fun assertNoChat(message: Component, after: LogMark?) {
        assertNoChat(literal(message), after)
    }

    /** チャットの行に限る正規表現（選択肢を含むパターンでも全体に \[CHAT\] が掛かるよう囲む）。 */
    private fun chatPattern(pattern: Regex): Regex = Regex("""\[CHAT\].*(?:${pattern.pattern})""", pattern.options)

    /** Component のプレーンテキストに一致する正規表現。 */
    private fun literal(message: Component): Regex = Regex(Regex.escape(PlainTextComponentSerializer.plainText().serialize(message)))

    // --- Adventure（§1.9） ------------------------------------------------------------

    /** 種類の AudienceCommands で計画し、送って記録する。 */
    private fun audience(method: String, plan: (AudienceCommands) -> List<CommandCall>) {
        AudienceDispatcher.dispatch(server, method, plan)
    }

    /** 対応するコマンドが無い操作。Adventure の契約（何もしない）と違い、黙って捨てずに投げる。 */
    private fun unsupported(method: String): Nothing = AudienceDispatcher.unsupported(server, method)

    /** identity() は UUID だけを持つ。 */
    override fun identity(): Identity = Identity.identity(uuid)

    /** NAME / UUID / DISPLAY_NAME / LOCALE。 */
    override fun pointers(): Pointers = pointers

    /** tellraw。 */
    override fun sendMessage(message: Component) {
        audience("sendMessage") { it.message(name, message) }
    }

    /** tellraw。 */
    override fun sendMessage(message: ComponentLike) {
        sendMessage(message.asComponent())
    }

    /** 署名やチャットの種類はチャットのセッションが必要なので送れない。 */
    override fun sendMessage(message: Component, boundChatType: ChatType.Bound): Unit = unsupported("sendMessage(Component, ChatType.Bound)")

    /** 同上。 */
    override fun sendMessage(message: ComponentLike, boundChatType: ChatType.Bound): Unit = unsupported("sendMessage(ComponentLike, ChatType.Bound)")

    /** 同上。 */
    override fun sendMessage(signedMessage: SignedMessage, boundChatType: ChatType.Bound): Unit = unsupported("sendMessage(SignedMessage, ChatType.Bound)")

    /** 同上。 */
    override fun deleteMessage(signedMessage: SignedMessage): Unit = unsupported("deleteMessage(SignedMessage)")

    /** 同上。 */
    override fun deleteMessage(signature: SignedMessage.Signature): Unit = unsupported("deleteMessage(SignedMessage.Signature)")

    /** title actionbar。 */
    override fun sendActionBar(message: Component) {
        audience("sendActionBar") { it.actionBar(name, message) }
    }

    /** title actionbar。 */
    override fun sendActionBar(message: ComponentLike) {
        sendActionBar(message.asComponent())
    }

    /** タブリストのヘッダーはバニラのコマンドが無い。 */
    override fun sendPlayerListHeader(header: Component): Unit = unsupported("sendPlayerListHeader")

    /** 同上。 */
    override fun sendPlayerListHeader(header: ComponentLike): Unit = unsupported("sendPlayerListHeader")

    /** 同上。 */
    override fun sendPlayerListFooter(footer: Component): Unit = unsupported("sendPlayerListFooter")

    /** 同上。 */
    override fun sendPlayerListFooter(footer: ComponentLike): Unit = unsupported("sendPlayerListFooter")

    /** 同上。 */
    override fun sendPlayerListHeaderAndFooter(header: Component, footer: Component): Unit = unsupported("sendPlayerListHeaderAndFooter")

    /** 同上。 */
    override fun sendPlayerListHeaderAndFooter(header: ComponentLike, footer: ComponentLike): Unit = unsupported("sendPlayerListHeaderAndFooter")

    /** times（あれば）→ subtitle → title の順に送る。title が表示のきっかけなので最後にする。 */
    override fun showTitle(title: Title) {
        audience("showTitle") { commands ->
            val times = title.times()?.let { commands.titlePart(name, TitlePart.TIMES, it) }.orEmpty()
            times + commands.titlePart(name, TitlePart.SUBTITLE, title.subtitle()) + commands.titlePart(name, TitlePart.TITLE, title.title())
        }
    }

    /** title times / subtitle / title。 */
    override fun <T : Any> sendTitlePart(part: TitlePart<T>, value: T) {
        audience("sendTitlePart") { it.titlePart(name, part, value) }
    }

    /** title clear。 */
    override fun clearTitle() {
        audience("clearTitle") { it.clearTitle(name) }
    }

    /** title reset。 */
    override fun resetTitle() {
        audience("resetTitle") { it.resetTitle(name) }
    }

    /** bossbar add / set。 */
    override fun showBossBar(bar: BossBar) {
        server.bossBars.show(bar, name)
    }

    /** bossbar set players / remove。 */
    override fun hideBossBar(bar: BossBar) {
        server.bossBars.hide(bar, name)
    }

    /** プレイヤーの位置で鳴らす。 */
    override fun playSound(sound: Sound) {
        audience("playSound") { it.playSound(name, sound, null) }
    }

    /** 座標を指定して鳴らす。 */
    override fun playSound(sound: Sound, x: Double, y: Double, z: Double) {
        audience("playSound") { it.playSound(name, sound, Triple(x, y, z)) }
    }

    /** 自分以外の発音源はコマンドで指定できない。 */
    override fun playSound(sound: Sound, emitter: Sound.Emitter) {
        if (emitter === Sound.Emitter.self()) playSound(sound) else unsupported("playSound(Sound, Sound.Emitter)")
    }

    /** stopsound。 */
    override fun stopSound(sound: Sound) {
        stopSound(sound.asStop())
    }

    /** stopsound。 */
    override fun stopSound(stop: SoundStop) {
        audience("stopSound") { it.stopSound(name, stop) }
    }

    /** 本を開かせるバニラのコマンドは無い。 */
    override fun openBook(book: Book.Builder): Unit = unsupported("openBook")

    /** 同上。 */
    override fun openBook(book: BookLike): Unit = unsupported("openBook")

    /** 同上。 */
    override fun openBook(book: Book): Unit = unsupported("openBook")

    /** リソースパックは設定の段階が必要。 */
    override fun sendResourcePacks(first: ResourcePackInfoLike, vararg others: ResourcePackInfoLike): Unit = unsupported("sendResourcePacks")

    /** 同上。 */
    override fun sendResourcePacks(request: ResourcePackRequestLike): Unit = unsupported("sendResourcePacks")

    /** 同上。 */
    override fun sendResourcePacks(request: ResourcePackRequest): Unit = unsupported("sendResourcePacks")

    /** 同上。 */
    override fun removeResourcePacks(request: ResourcePackRequestLike): Unit = unsupported("removeResourcePacks")

    /** 同上。 */
    override fun removeResourcePacks(request: ResourcePackRequest): Unit = unsupported("removeResourcePacks")

    /** 同上。 */
    override fun removeResourcePacks(first: ResourcePackInfoLike, vararg others: ResourcePackInfoLike): Unit = unsupported("removeResourcePacks")

    /** 同上。 */
    override fun removeResourcePacks(ids: Iterable<UUID>): Unit = unsupported("removeResourcePacks")

    /** 同上。 */
    override fun removeResourcePacks(id: UUID, vararg others: UUID): Unit = unsupported("removeResourcePacks")

    /** 同上。 */
    override fun clearResourcePacks(): Unit = unsupported("clearResourcePacks")

    /** ダイアログはコマンドの計画に渡せない（DialogLike の中身が見えない）。 */
    override fun showDialog(dialog: DialogLike): Unit = unsupported("showDialog")

    /** 同上。 */
    override fun closeDialog(): Unit = unsupported("closeDialog")

    /** 表示用。 */
    override fun toString(): String = "Player($name on ${server.resultId})"

    /** 生成と定数。 */
    companion object {
        /** キーコードの表（ディスプレイごとにキャッシュし、ディスプレイを起動し直したら読み直す）。 */
        val KEYCODES: KeycodeResolver = KeycodeResolver.xmodmap()

        /** 失敗時の撮影の名前（予約）。 */
        const val FAILURE_SCREENSHOT: String = "failure"

        /** press_key の action。 */
        private const val ACTION_PRESS_KEY = "press_key"

        /** チャット欄が開くまで待つ時間（player_session.py:91）。 */
        private val CHAT_OPEN_WAIT: Duration = 500.milliseconds

        /** 撮影の待ちの上限（player_session.py:116）。 */
        private val SCREENSHOT_TIMEOUT: Duration = 15.seconds

        /** 撮影の待ちの間隔。 */
        private val SCREENSHOT_POLL: Duration = 250.milliseconds

        /** 書き込みが終わったと見なすまでサイズが変わらない時間。 */
        private val SCREENSHOT_STABLE: Duration = 500.milliseconds

        /** クライアントを止める猶予（client.py:103）。 */
        private val CLIENT_GRACE: Duration = 10.seconds

        /** Xvfb を止める猶予（xvfb.py:47）。 */
        private val DISPLAY_GRACE: Duration = 5.seconds

        /** プレイヤーのクライアントとディスプレイを作る（起動はしない）。 */
        fun create(server: ServerInstance, profile: PlayerProfile, portablemc: Path): PlayerSession {
            val dirs = server.dirs
            val client = ClientProcess(
                player = profile.name,
                version = server.type.minecraftVersion,
                portablemc = portablemc,
                // バージョンやアセットのキャッシュは全プレイヤーで共有し、設定・ログはプレイヤーごとに分ける
                mainDir = dirs.minecraftCache,
                clientDir = dirs.clientDir(profile.name),
                heap = server.definition.clientHeap,
                launchLog = dirs.launchLog(profile.name),
            )
            return PlayerSession(server, profile, client, VirtualDisplay(dirs.xvfbLog(profile.name)))
        }
    }
}
