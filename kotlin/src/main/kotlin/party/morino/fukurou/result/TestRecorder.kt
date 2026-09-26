package party.morino.fukurou.result

import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.enums.TestFailurePhase
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.step.StepResult
import party.morino.fukurou.result.model.test.LogRange
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.result.model.test.ScreenshotInfo
import party.morino.fukurou.result.model.test.TestFailure
import party.morino.fukurou.result.model.test.TestPlayer
import party.morino.fukurou.result.model.test.TestResult
import party.morino.fukurou.result.output.StatusMapper
import party.morino.fukurou.result.output.Timestamps
import java.time.Clock
import java.time.Instant

/**
 * テスト 1 件の結果（result/recorder.py:78-257 TestRecorder）。
 *
 * start() までは skipped（not run）で、実行した分だけ内容が埋まる。最初の失敗だけを残し、
 * finish() / skip() の後に届いた記録（期限切れで置き去りにしたレーンなど）は警告を出して無視する。
 *
 * Python と違ってステップを前もって計画できないので、steps には実際に始まったステップだけが並ぶ。
 * parallel のステップは BlockRecorder に溜め、ブロックの終了時にレーン 0 から添字を付ける。
 *
 * @param planned 計画したテスト
 * @param warn harness.log への警告
 * @param clock 時刻の取得元
 * @param nanoTime 所要時間の測定に使う単調な時計
 */
internal class TestRecorder(
    private val planned: PlannedTest,
    private val warn: (String) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** tests[].id。 */
    val id: String get() = planned.id

    /** skipped の理由。start() まではスタブの NOT_RUN。 */
    private var skipReason: String? = NOT_RUN

    /** 実行したセッション。 */
    private var session: Int? = null

    /** 参加プレイヤー。testStarted で渡されたものを優先する。 */
    private var players: List<PlayerProfile> = planned.players

    /** リセットの記録。 */
    private var reset: ResetInfo? = null

    /** 最初の失敗。stepIndex は build() で仮 id から付け直すので、ここでは使わない。 */
    private var failure: TestFailure? = null

    /** 最初の失敗のステップの仮 id。 */
    private var failureStep: Long? = null

    /** 最初の失敗の status（§6.4 の「その他の例外」は scenario でも error）。 */
    private var failureStatus: TestStatus = TestStatus.ERROR

    /** 添字を付け終えたステップ（steps の順）。 */
    private val steps = mutableListOf<StepEvent>()

    /** 仮 id → steps の添字。 */
    private val indexOf = mutableMapOf<Long, Int>()

    /** 開いている parallel ブロックのステップ。 */
    private val blocks = BlockRecorder()

    /** 時間順で最初に失敗したステップの仮 id。失敗にステップが示されないときの stepIndex に使う。 */
    private var firstFailedStep: Long? = null

    /** 撮ったスクリーンショットと、撮ったステップの仮 id。 */
    private val screenshots = mutableListOf<Pair<ScreenshotInfo, Long?>>()

    /** ログのパス → このテストの間の行。 */
    private var logRanges: Map<String, LogRange>? = null

    /** 開始時刻。 */
    private var startedAt: Instant? = null

    /** 開始時の単調な時計の値。 */
    private var startedNanos: Long? = null

    /** 所要時間。 */
    private var durationMs: Long? = null

    /** finish() / skip() の後か。 */
    private var finished = false

    /** まだ始まっていない（not run のスタブのまま）か。 */
    val isNotRun: Boolean
        @Synchronized get() = !finished && skipReason == NOT_RUN

    /** skipped（理由あり）/ 失敗の status / passed。終わる前の書き出しでは成功とは見なさない（recorder.py:99）。 */
    val status: TestStatus
        @Synchronized get() = when {
            skipReason != null -> TestStatus.SKIPPED
            failure != null -> failureStatus
            finished -> TestStatus.PASSED
            else -> TestStatus.ERROR
        }

    /**
     * テストを開始する。以後は skipped ではなくなる。
     *
     * @param session 実行するセッションの index
     * @param players 参加プレイヤー（空なら計画のもの）
     */
    @Synchronized
    fun start(session: Int, players: List<PlayerProfile> = emptyList()) {
        if (finished) return warn("test $id was started after it was closed; ignoring the start")
        skipReason = null
        this.session = session
        if (players.isNotEmpty()) this.players = players
        startedAt = clock.instant()
        startedNanos = nanoTime()
    }

    /** リセットの記録。失敗していればテストは error（phase reset）。 */
    @Synchronized
    fun setReset(reset: ResetInfo) {
        if (finished) return warn("reset of $id was recorded after the test was closed; ignoring it")
        this.reset = reset
        reset.error?.let { failLocked(TestFailurePhase.RESET, it, null, TestStatus.ERROR) }
    }

    /** ステップの開始。parallel の外のステップはここで最終的な添字が決まる。 */
    @Synchronized
    fun stepStarted(event: StepEvent) {
        if (finished) return warn("step ${event.provisionalId} of $id started after the test was closed; ignoring it")
        val parallel = event.parallel
        if (parallel != null && !blocks.isFlushed(parallel.block)) {
            // レーンのステップは交互に届くので、ブロックが終わるまで溜める
            blocks.start(event)
        } else {
            if (parallel != null) warn("step ${event.provisionalId} of $id started after its parallel block ${parallel.block} finished")
            append(event)
        }
    }

    /** ステップの終了。期限切れで failed と記録した後に遅れて届いた passed は無視する。 */
    @Synchronized
    fun stepFinished(event: StepEvent) {
        if (finished) return warn("step ${event.provisionalId} of $id finished after the test was closed; ignoring it")
        val index = indexOf[event.provisionalId]
        // 猶予の間に返ったレーンのステップは、ブロックが閉じるまで BlockRecorder の中にある
        val previous = index?.let { steps[it] } ?: blocks.find(event.provisionalId)
        if (previous?.status == StepStatus.FAILED && event.status == StepStatus.PASSED) {
            // ハーネスの判断を優先し、そのステップが遅れて撮ったスクリーンショットも載せない（recorder.py:113-124）
            warn("step ${event.provisionalId} of $id passed after it was recorded as failed; keeping the failure")
            screenshots.removeAll { (shot, step) -> step == event.provisionalId && shot.path == event.screenshot }
            return
        }
        when {
            index != null -> steps[index] = event.copy(startedAt = steps[index].startedAt)
            // 溜めていない parallel のステップ（stepStarted が来なかった）は開始と終了を同時に受け取る
            !blocks.update(event) -> stepStarted(event)
        }
        // parallel で複数のレーンが失敗したら、時間順で先に失敗したステップを failure に結びつける
        if (event.status == StepStatus.FAILED && firstFailedStep == null) firstFailedStep = event.provisionalId
    }

    /** parallel ブロックの終了。そのステップをレーン 0 から順に steps へ入れる。 */
    @Synchronized
    fun blockFinished(block: Int) {
        if (finished) return warn("parallel block $block of $id finished after the test was closed; ignoring it")
        blocks.flush(block).forEach(::append)
    }

    /**
     * スクリーンショットを足す。
     *
     * @param info スクリーンショット（stepIndex は無視し、provisionalStepId から付け直す）
     * @param provisionalStepId 撮ったステップの仮 id
     */
    @Synchronized
    fun addScreenshot(info: ScreenshotInfo, provisionalStepId: Long?) {
        if (finished) return warn("screenshot ${info.player}/${info.name} of $id was taken after the test was closed; ignoring it")
        screenshots += info to provisionalStepId
    }

    /**
     * 失敗を記録する。最初の失敗だけを残す（後続の失敗は最初の失敗の結果であることが多いため）。
     *
     * @param status 失敗の status（既定は段階から導く: ステップの段階なら failed、それ以外は error）
     */
    @Synchronized
    fun fail(
        phase: TestFailurePhase,
        message: String,
        provisionalStepId: Long? = null,
        status: TestStatus = defaultStatus(phase),
    ) {
        if (finished) return warn("failure of $id was recorded after the test was closed; ignoring it: $message")
        failLocked(phase, message, provisionalStepId, status)
    }

    /**
     * 実行を終える。結末が失敗なら（先に記録した失敗が無いときだけ）failure にする。以後の記録は無視する。
     *
     * @param outcome StatusMapper の結末
     * @param logRanges ログのパス → このテストの間の行
     */
    @Synchronized
    fun finish(outcome: TestOutcome, logRanges: Map<String, LogRange> = emptyMap()) {
        if (finished) return warn("test $id was finished twice; keeping the first result")
        if (outcome.status == TestStatus.SKIPPED) return skip(outcome.skipReason ?: outcome.message ?: "skipped")
        // blockFinished が来なかったブロック（例外で抜けた）も、添字を付け直せるよう閉じておく
        blocks.flushAll().forEach(::append)
        if (outcome.status != TestStatus.PASSED) {
            val phase = outcome.failurePhase ?: TestFailurePhase.SCENARIO
            failLocked(phase, outcome.message.orEmpty(), outcome.provisionalStepId ?: firstFailedStep, outcome.status)
        }
        // 終わらなかったステップ（置き去りにしたレーン）は、テストを止めた理由で failed にする
        val reason = failure?.message?.takeIf { it.isNotEmpty() } ?: UNFINISHED
        steps.replaceAll { step -> if (step.status == null) step.copy(status = StepStatus.FAILED, error = reason) else step }
        this.logRanges = logRanges.ifEmpty { null }
        finished = true
        durationMs = startedNanos?.let { (nanoTime() - it) / 1_000_000 }
    }

    /**
     * 走らなかった（または走り切れなかった）テストにする。契約どおり実行の痕跡は残さない。終端の状態。
     *
     * @throws IllegalArgumentException 理由が空
     */
    @Synchronized
    fun skip(reason: String) {
        // 理由が無いと、ビューアで「なぜ走らなかったか」を示せない
        require(reason.isNotEmpty()) { "a skipped test needs a reason" }
        if (finished) return warn("test $id was skipped after it was closed; keeping the first result")
        finished = true
        skipReason = reason
        session = null
        reset = null
        failure = null
        failureStep = null
        steps.clear()
        indexOf.clear()
        blocks.clear()
        screenshots.clear()
        logRanges = null
        startedAt = null
        durationMs = null
    }

    /** 現時点の内容から TestResult を作る。開いている parallel ブロックのステップも閉じたときと同じ順で末尾に載せる。 */
    @Synchronized
    fun build(): TestResult {
        val notRun = skipReason != null
        // 途中の書き出し（シャットダウンフックなど）でも仮 id を指せるよう、開いているブロックに仮の添字を付ける
        val all = if (notRun) emptyList() else steps + blocks.snapshot()
        val indices = all.withIndex().associate { (index, step) -> step.provisionalId to index }
        return TestResult(
            id = planned.id,
            name = planned.name,
            order = planned.order,
            source = planned.source,
            sha256 = planned.sha256,
            tags = planned.tags,
            isolation = planned.isolation,
            timeout = planned.timeoutSeconds,
            versions = planned.versions,
            session = session,
            status = status,
            skipReason = skipReason,
            players = players.map { TestPlayer(it.name, it.op) },
            reset = reset,
            steps = all.mapIndexed { index, step -> step.toResult(index) },
            failure = failure?.copy(stepIndex = failureStep?.let(indices::get)),
            screenshots = screenshots.map { (shot, step) -> shot.copy(stepIndex = step?.let(indices::get)) },
            logRanges = logRanges,
            startedAt = startedAt?.let(Timestamps::seconds),
            durationMs = durationMs,
        )
    }

    /** ステップに最終的な添字を付けて steps の末尾へ入れる。 */
    private fun append(event: StepEvent) {
        indexOf[event.provisionalId] = steps.size
        steps += event
    }

    /** 最初の失敗だけを残す。呼び出し側がロックを持っている前提。 */
    private fun failLocked(phase: TestFailurePhase, message: String, provisionalStepId: Long?, status: TestStatus) {
        if (failure != null) return
        failure = TestFailure(phase, message)
        failureStep = provisionalStepId
        failureStatus = status
    }

    /** 出来事を契約のステップにする。終わっていないステップは実行前と同じ skipped で表す。 */
    private fun StepEvent.toResult(index: Int): StepResult =
        StepResult(
            index = index,
            phase = phase,
            fixture = fixture,
            on = on,
            action = action,
            label = label,
            status = status ?: StepStatus.SKIPPED,
            durationMs = durationMs,
            error = error,
            screenshot = screenshot,
            parallel = parallel,
            // Kotlin 版は repeat を記録しない（§6.2）
            repeat = null,
            startedAt = Timestamps.millis(startedAt),
            finishedAt = finishedAt?.let(Timestamps::millis),
        )

    /** 定数。 */
    companion object {
        /** 計画直後のスタブで全テストに付ける理由。ジョブが途中で消えても「走らなかった」と分かる。 */
        const val NOT_RUN: String = "not run"

        /** 理由の分からないまま終わらなかったステップの error。 */
        private const val UNFINISHED = "the step did not finish before the test ended"

        /** 段階から status を導く（model.py の STEP_FAILURE_PHASES）。 */
        private fun defaultStatus(phase: TestFailurePhase): TestStatus =
            if (phase in StatusMapper.STEP_FAILURE_PHASES) TestStatus.FAILED else TestStatus.ERROR
    }
}
