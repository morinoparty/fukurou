package party.morino.fukurou.result

import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.RunStatus
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.run.FukurouInfo
import party.morino.fukurou.result.model.run.JavaInfo
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.run.ResultV2
import party.morino.fukurou.result.model.run.RunFailure
import party.morino.fukurou.result.model.run.Summary
import party.morino.fukurou.result.model.session.LogInfo
import party.morino.fukurou.result.model.session.LogKind
import party.morino.fukurou.result.model.session.SessionInfo
import party.morino.fukurou.result.model.suite.PlayerInfo
import party.morino.fukurou.result.model.suite.PluginInfo
import party.morino.fukurou.result.model.suite.SelectionInfo
import party.morino.fukurou.result.model.suite.SuiteInfo
import party.morino.fukurou.result.model.test.LogRange
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.result.model.test.ScreenshotInfo
import party.morino.fukurou.result.model.test.TestResult
import party.morino.fukurou.result.output.ArtifactLayout
import party.morino.fukurou.result.output.CiEnvironment
import party.morino.fukurou.result.output.Timestamps
import party.morino.fukurou.spi.model.PlatformInfo
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.Properties

/**
 * 1 サーバー（1 つの result.json）分の実行の結果（result/recorder.py:274-373 RunRecorder）。
 *
 * エンジンの出来事（RunObserver）を受け取り、途中で失敗してもその時点までの内容で result.json を書けるようにする。
 * 書き出しは §6.5 の時点（計画直後・各テストの後・run の失敗・セッションの終了・run の終了）で自動で行い、
 * リースの close やシャットダウンフックは write() を直接呼ぶ。すべての操作は 1 つのロックで直列にする。
 *
 * @param runId result.json の id（ResultIds.runId で作ったもの）
 * @param label サーバーの label
 * @param minecraft 起動前に分かっているサーバーの情報（serverReady で build / channel を埋める）
 * @param fukurou 結果を書いた fukurou の情報
 * @param suite スイート（JUnit 拡張）の設定
 * @param selection 選択したテスト
 * @param writer result.json の書き出し先。null なら書かない（単体テスト用）
 * @param warn harness.log への警告
 * @param env 環境変数（ci の判定に使う）
 * @param clock 時刻の取得元
 * @param nanoTime 所要時間の測定に使う単調な時計
 */
internal class RunRecorder(
    private val runId: String,
    private val label: String?,
    private var minecraft: MinecraftInfo,
    private val fukurou: FukurouInfo,
    private val suite: SuiteInfo?,
    private val selection: SelectionInfo,
    private val writer: ResultWriter?,
    private val warn: (String) -> Unit = {},
    env: Map<String, String> = System.getenv(),
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
) : RunObserver {
    /** 開始時刻。 */
    private val startedAt: Instant = clock.instant()

    /** 開始時の単調な時計の値。 */
    private val startedNanos: Long = nanoTime()

    /** GitHub Actions の情報（ローカルでは null）。 */
    private val ci = CiEnvironment.fromEnv(env)

    /** サーバーを起動した Java。 */
    private var java = JavaInfo()

    /** 入れたプラグイン。 */
    private var plugins: List<PluginInfo> = emptyList()

    /** 参加させるプレイヤー（全テストの和集合、登場順）。 */
    private val players = mutableListOf<PlayerInfo>()

    /** セッション（index の順）。 */
    private val sessions = mutableListOf<SessionInfo>()

    /** テスト（計画順。テンプレートの実行などで後から足したものは末尾）。 */
    private val tests = linkedMapOf<String, TestRecorder>()

    /** run を止めた最初の失敗。 */
    private var failure: RunFailure? = null

    /** セッションに属さないログ。 */
    private val logs = listOf(LogInfo(LogKind.HARNESS, ArtifactLayout.HARNESS_LOG))

    /** runFinished で確定した終了時刻。それまでは書き出しの時刻を使う。 */
    private var finishedAt: Instant? = null

    /** runFinished で確定した所要時間。 */
    private var durationMs: Long? = null

    /** 現時点の run の status。 */
    val status: RunStatus
        @Synchronized get() = deriveStatus(failure, tests.values.map { it.status })

    /** 計画したテストを not run のスタブとして登録し、result.json を書く（ダウンロードより前）。 */
    @Synchronized
    override fun runPlanned(tests: List<PlannedTest>) {
        tests.forEach(::planLocked)
        writeQuietly("the plan stub")
    }

    /**
     * 計画に無かったテスト（テンプレートの n 回目など）を足す。既にある id なら何もしない。
     */
    @Synchronized
    fun plan(test: PlannedTest) {
        planLocked(test)
    }

    /** セッションの開始。index は追加順と一致させる。 */
    @Synchronized
    override fun sessionStarted(index: Int, kind: SessionKind) {
        // 番号が飛ぶと sessions[].index と配列の位置がずれ、ビューアの対応づけが壊れる
        require(index == sessions.size) { "session $index started, but ${sessions.size} sessions exist" }
        sessions += SessionInfo(index = index, kind = kind, startedAt = Timestamps.seconds(clock.instant()))
    }

    /** サーバーの準備完了。minecraft / java / plugins を埋める。 */
    @Synchronized
    override fun serverReady(info: PlatformInfo, plugins: List<PluginInfo>) {
        minecraft = MinecraftInfo(version = info.version, server = info.serverKind, build = info.build, channel = info.channel)
        java = JavaInfo(info.javaMajor)
        this.plugins = plugins.toList()
    }

    /** プレイヤーの参加。players[].joined と、今のセッションの players に反映する。 */
    @Synchronized
    override fun playerJoined(name: String) {
        val position = players.indexOfFirst { it.name == name }
        if (position >= 0) players[position] = players[position].copy(joined = true) else players += PlayerInfo(name, joined = true)
        val current = sessions.indexOfLast { it.finishedAt == null }
        if (current < 0) return warn("$name joined outside a session")
        val session = sessions[current]
        if (name !in session.players) sessions[current] = session.copy(players = session.players + name)
    }

    /** テストの開始。 */
    @Synchronized
    override fun testStarted(testId: String, session: Int, players: List<PlayerProfile>) {
        // 計画に無いテストは name や source が分からないので、先に plan() で足してもらう
        val test = requireNotNull(tests[testId]) { "test $testId was not planned; call plan() before testStarted" }
        require(session in sessions.indices) { "test $testId started in unknown session $session" }
        test.start(session, players)
        players.forEach { addPlayer(it.name) }
        val info = sessions[session]
        sessions[session] = info.copy(tests = info.tests + testId)
    }

    /** リセットの終了。 */
    @Synchronized
    override fun resetFinished(testId: String, reset: ResetInfo) {
        test(testId)?.setReset(reset)
    }

    /** ステップの開始。 */
    @Synchronized
    override fun stepStarted(testId: String, event: StepEvent) {
        test(testId)?.stepStarted(event)
    }

    /** ステップの終了。 */
    @Synchronized
    override fun stepFinished(testId: String, event: StepEvent) {
        test(testId)?.stepFinished(event)
    }

    /** parallel ブロックの終了。 */
    @Synchronized
    override fun blockFinished(testId: String, block: Int) {
        test(testId)?.blockFinished(block)
    }

    /** スクリーンショットの撮影。 */
    @Synchronized
    override fun screenshotTaken(testId: String, info: ScreenshotInfo, provisionalStepId: Long?) {
        test(testId)?.addScreenshot(info, provisionalStepId)
    }

    /** テストの終了。result.json を書き直す。 */
    @Synchronized
    override fun testFinished(testId: String, outcome: TestOutcome, logRanges: Map<String, LogRange>) {
        test(testId)?.finish(outcome, logRanges)
        writeQuietly("test $testId")
    }

    /** テストを実行しなかった。result.json を書き直す。 */
    @Synchronized
    override fun testSkipped(testId: String, reason: String) {
        test(testId)?.skip(reason)
        writeQuietly("skipped test $testId")
    }

    /** run の失敗。最初の失敗だけを残す（後続の失敗は最初の失敗の結果であることが多いため）。 */
    @Synchronized
    override fun runFailed(phase: RunFailurePhase, message: String) {
        if (failure == null) failure = RunFailure(phase, message) else warn("a later run failure was not recorded: $message")
        writeQuietly("the run failure")
    }

    /** セッションの終了。回収したログは、再起動時に回収済みのものへ足す。 */
    @Synchronized
    override fun sessionFinished(index: Int, logs: List<LogInfo>, failure: String?) {
        val session = sessions.getOrNull(index) ?: return warn("unknown session $index finished")
        // 同じパスのログは 1 回だけ載せる
        val merged = logs.fold(session.logs) { acc, info -> if (acc.any { it.path == info.path }) acc else acc + info }
        sessions[index] = session.copy(
            finishedAt = Timestamps.seconds(clock.instant()),
            logs = merged,
            failure = session.failure ?: failure,
        )
        writeQuietly("session $index")
    }

    /** run の終了。終了時刻を確定して書く。 */
    @Synchronized
    override fun runFinished() {
        if (finishedAt == null) {
            finishedAt = clock.instant()
            durationMs = (nanoTime() - startedNanos) / 1_000_000
        }
        writeQuietly("the end of the run")
    }

    /**
     * まだ走っていない（not run の）テストを理由付きの skipped にし、その id を返す。
     */
    @Synchronized
    fun skipPending(reason: String): List<String> {
        val pending = tests.values.filter { it.isNotRun }.onEach { it.skip(reason) }.map { it.id }
        // 呼び出しの順序に関わらず、飛ばした理由がすぐ result.json に残るようにする
        if (pending.isNotEmpty()) writeQuietly("skipping pending tests")
        return pending
    }

    /** 現時点の内容から ResultV2 を作る。summary と status は tests から導く。 */
    @Synchronized
    fun build(): ResultV2 {
        val results = tests.values.map { it.build() }
        val now = clock.instant()
        return ResultV2(
            id = runId,
            label = label,
            status = deriveStatus(failure, results.map { it.status }),
            fukurou = fukurou,
            minecraft = minecraft,
            java = java,
            plugins = plugins,
            suite = suite,
            selection = selection,
            players = players.toList(),
            summary = summarize(results),
            sessions = sessions.toList(),
            tests = results,
            failure = failure,
            logs = logs,
            startedAt = Timestamps.seconds(startedAt),
            finishedAt = Timestamps.seconds(finishedAt ?: now),
            durationMs = durationMs ?: ((nanoTime() - startedNanos) / 1_000_000),
            ci = ci,
        )
    }

    /**
     * result.json を書く（リースの close やシャットダウンフックから）。
     *
     * @throws IOException 書き込みに失敗した
     */
    @Synchronized
    fun write(): ResultV2 {
        val result = build()
        writer?.write(result)
        return result
    }

    /** 計画を 1 件足す。呼び出し側がロックを持っている前提。 */
    private fun planLocked(test: PlannedTest) {
        if (test.id in tests) return
        tests[test.id] = TestRecorder(test, warn, clock, nanoTime)
        test.players.forEach { addPlayer(it.name) }
    }

    /** players[] に無ければ足す。 */
    private fun addPlayer(name: String) {
        if (players.none { it.name == name }) players += PlayerInfo(name)
    }

    /** 記録係を探す。知らないテストの出来事は harness.log にだけ残す。 */
    private fun test(testId: String): TestRecorder? = tests[testId] ?: run {
        warn("event for unknown test $testId ignored")
        null
    }

    /** 出来事の途中の書き出し。失敗してもテストの結果を変えないよう、警告にとどめる。 */
    private fun writeQuietly(reason: String) {
        try {
            write()
        } catch (error: IOException) {
            warn("could not write result.json after $reason: $error")
        }
    }

    /** status の導出と、version.properties の読み込み。 */
    companion object {
        /**
         * run の status（model.py derive_run_status）。インフラの失敗があれば error、
         * いずれかのテストが failed / error なら failed、それ以外は passed。
         */
        fun deriveStatus(failure: RunFailure?, statuses: List<TestStatus>): RunStatus =
            when {
                failure != null -> RunStatus.ERROR
                statuses.any { it == TestStatus.FAILED || it == TestStatus.ERROR } -> RunStatus.FAILED
                else -> RunStatus.PASSED
            }

        /** ステータスごとの件数（model.py summarize_tests）。 */
        fun summarize(tests: List<TestResult>): Summary {
            val counts = tests.groupingBy { it.status }.eachCount()
            return Summary(
                total = tests.size,
                passed = counts[TestStatus.PASSED] ?: 0,
                failed = counts[TestStatus.FAILED] ?: 0,
                error = counts[TestStatus.ERROR] ?: 0,
                skipped = counts[TestStatus.SKIPPED] ?: 0,
            )
        }

        /** ビルド時に生成した META-INF/fukurou/version.properties から fukurou の情報を作る。 */
        fun loadFukurouInfo(): FukurouInfo {
            val properties = Properties()
            // リソースが無い（IDE から直接実行した）場合も書き出しを止めないよう、既定値にする
            RunRecorder::class.java.getResourceAsStream("/META-INF/fukurou/version.properties")?.use(properties::load)
            return FukurouInfo(
                version = properties.getProperty("version", "dev"),
                portablemc = properties.getProperty("portablemc", "5.0.4"),
                runner = "kotlin",
            )
        }
    }
}
