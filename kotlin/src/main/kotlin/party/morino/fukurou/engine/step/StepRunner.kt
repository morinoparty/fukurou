package party.morino.fukurou.engine.step

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.result.StepEvent
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.step.ParallelInfo
import party.morino.fukurou.result.output.StatusMapper
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import kotlin.time.Duration

/**
 * 1 ステップを実行して記録する。
 *
 * 記録先のテストは StepScope、無ければ実行中のテスト（ActiveTests）から探す。どちらも無ければ実行だけして
 * harness.log に 1 行残す。例外の分類（クライアントの死亡 → client、サーバーの死亡 → サーバーを dead に、
 * 期限切れ → timeout）はすべてここに集める。レーンからも同時に呼ばれる。
 */
internal object StepRunner {
    /** サーバーへのステップの on。プレイヤー名としては予約されている（PlayerProfile）。 */
    const val ON_SERVER: String = "server"

    /**
     * host のサーバーのステップを実行して記録する。
     *
     * @param host ステップが属するサーバー（pause のようにサーバーに属さないステップは null）
     * @param on "server" / プレイヤー名 / null
     * @param action アクション名（result.json の steps[].action）
     * @param label 一覧表示用の説明
     * @param inputPlayer クライアントへの入力なら、そのプレイヤー（parallel で同じプレイヤーへの入力を 1 レーンに限る）
     * @param screenshotOf 戻り値から screenshot アクションの保存先を取り出す
     * @param body ステップの本体。記録先のテストと仮 id を受け取る（記録しないときは両方 null）
     */
    suspend fun <T> step(
        host: StepHost?,
        on: String?,
        action: String,
        label: String,
        inputPlayer: String? = null,
        screenshotOf: (T) -> String? = { null },
        body: suspend (TestRun?, Long?) -> T,
    ): T {
        val scope = StepScope.current()
        val run = resolve(scope, host)
        return stepIn(run, scope, host, on, action, label, inputPlayer, screenshotOf, body = body)
    }

    /**
     * 記録される待ち（action "wait"、on null、label "1.5s"）。テストの残り時間で打ち切る。
     *
     * StepScope が無く実行中のテストが複数ある（1 つのテストを 2 台のサーバーで実行している）ときは、全部に記録する。
     */
    suspend fun pause(duration: Duration) {
        hostless("wait", "${seconds(duration)}s") { delay(duration) }
    }

    /**
     * サーバーに属さないステップ（on null。wait・step・eventually・await_until）を実行して記録する。
     *
     * StepScope があればその記録先だけ、無ければ（または StepScope.implicit なら）実行中のテストすべてに記録する
     * （§1.8。1 つのテストを 2 台のサーバーで実行しているときは両方に載る）。記録先ごとに入れ子のステップにし、body 自体は 1 回だけ実行する。
     */
    suspend fun <T> hostless(action: String, label: String, body: suspend () -> T): T = anchored(action, label) { body() }

    /**
     * hostless と同じく実行して記録し、body には記録先のテスト → そのテストでのこのステップの仮 id を渡す
     * （eventually / awaitUntil が試行の中のスクリーンショットを自分のステップに結びつけるのに使う）。
     * 記録しなかった（quiet の中・テストの外）テストは含まない。
     */
    suspend fun <T> anchored(action: String, label: String, body: suspend (anchors: Map<TestRun, Long>) -> T): T {
        val scope = StepScope.current()
        // スコープがあればその記録先だけ。無ければ実行中のテストすべて（§1.8）。repeat / parallel が
        // スコープの無いところで作ったスコープ（implicit）も、無いときと同じに扱う
        val runs = if (scope != null && !scope.implicit) listOfNotNull(scope.run) else ActiveTests.all()
        // 外側の記録先から順に仮 id を取るので、body が走るときには全部そろっている
        val anchors = ConcurrentHashMap<TestRun, Long>()
        val nested = runs.fold(suspend { body(anchors) }) { inner, run ->
            {
                stepIn(run, scope, run.host, null, action, label, null, { null }, hostless = true) { _, id ->
                    if (id != null) anchors[run] = id
                    inner()
                }
            }
        }
        return nested()
    }

    /**
     * スクリーンショットの名前を取り、結びつけるステップの仮 id を返す。
     *
     * 記録するステップ（stepId が null でない）ではそのステップに結びつけ、同じテスト・プレイヤーで名前の重複を許さない。
     * 記録しない試行（eventually / awaitUntil の中、stepId が null）では、同じブロックの中での撮り直しを許し
     * （後の試行が上書きする）、ブロック自身のステップに結びつける（無ければ null。どのステップにも結びつけない）。
     *
     * @throws IllegalArgumentException 別のステップ・ブロックが同じ名前を使った
     */
    suspend fun claimScreenshot(run: TestRun, player: String, name: String, stepId: Long?): Long? {
        if (stepId != null) {
            run.claimScreenshot(player, name, stepId)
            return stepId
        }
        val block = StepScope.current()?.quietBlock
        // quiet の外で仮 id が無いことは無いが、そのときもどのステップにも結びつけずに撮る
        run.claimScreenshot(player, name, block ?: Any())
        return block?.anchorFor(run)
    }

    /**
     * suspend でない呼び出し（assertNoLog / assertNoChat）のステップ。待ちが無いので期限の打ち切りはしない。
     */
    fun <T> instant(host: StepHost?, on: String?, action: String, label: String, body: () -> T): T {
        val scope = StepScope.currentBlocking()
        val run = resolve(scope, host) ?: return body().also { host?.log("step outside a test: $action on ${on ?: "-"}: $label") }
        if (scope?.quiet == true) {
            // eventually の試行。記録はせず、例外の分類だけをする
            return try {
                body()
            } catch (error: Throwable) {
                throw classifyQuiet(run, error)
            }
        }
        val lane = scope?.lane
        val event = begin(run, scope, on, action, label)
        val started = System.nanoTime()
        try {
            return body().also { finish(run, event, StepStatus.PASSED, started, null, null) }
        } catch (error: Throwable) {
            throw classify(run, event, started, error)
        } finally {
            if (lane != null) scope.block?.stepEnded(lane, event)
        }
    }

    /**
     * 記録先 run を決めてステップを実行する。
     *
     * @param hostless サーバーに属さないステップ（hostless）。中のステップの例外がそのまま通り抜けるだけなので、
     *   サーバーの死亡を自分の記録先のサーバーのものとしない（2 台目のサーバーの死亡で 1 台目まで dead にしない）
     */
    private suspend fun <T> stepIn(
        run: TestRun?,
        scope: StepScope?,
        host: StepHost?,
        on: String?,
        action: String,
        label: String,
        inputPlayer: String?,
        screenshotOf: (T) -> String?,
        hostless: Boolean = false,
        body: suspend (TestRun?, Long?) -> T,
    ): T {
        if (run == null) {
            // テストの外（サーバーの起動直後など）。result.json には載せず、何をしたかだけ残す
            host?.log("step outside a test: $action on ${on ?: "-"}: $label")
            return body(null, null)
        }
        if (scope?.quiet == true) return quietStep(run, scope, inputPlayer, body)
        val runHost = run.host
        // 期限を過ぎていれば、ステップを始めずにテストを timeout にする
        run.deadline.check(run.stepCount.toInt())
        val event = begin(run, scope, on, action, label)
        val block = scope?.block
        val lane = scope?.lane
        val started = System.nanoTime()
        try {
            val result = try {
                // ステップ 1 つもテストの残り時間を超えて待たない（どの待ちも期限で打ち切る、§4.11）
                withTimeout(run.deadline.remaining()) {
                    // ステップの前に全員の生存を確かめる。死んでいれば client の失敗として記録される
                    runHost.checkClientsAlive()
                    if (inputPlayer != null && block != null && lane != null) block.guard.onInput(inputPlayer, lane)
                    body(run, event.provisionalId)
                }
            } catch (timeout: TimeoutCancellationException) {
                // 自分の withTimeout がテストの期限で切れた。呼び出し元の取り消しとは区別して timeout にする
                if (!run.deadline.isExceeded) throw timeout
                throw HarnessTimeoutException(
                    "the test exceeded its timeout of ${run.deadline.timeout.inWholeSeconds}s during step ${event.provisionalId}",
                )
            }
            finish(run, event, StepStatus.PASSED, started, null, screenshotOf(result))
            return result
        } catch (cancelled: CancellationException) {
            // ステップ自身の失敗ではないので死亡の判定はしない
            if (block?.cancelReason != null || run.deadline.isExceeded) {
                // ハーネスが打ち切った（parallel の期限切れ・兄弟のレーンでのサーバーの死亡）。テストの失敗の候補にする
                val reason = block?.cancelReason ?: cancelled.message ?: "cancelled"
                runHost.warn("step ${event.provisionalId} cancelled: $reason")
                val failed = finish(run, event, StepStatus.FAILED, started, reason, null)
                run.stepFailed(failed, null)
            } else {
                // 呼び出し元が取り消した（利用者の withTimeoutOrNull で「来ないこと」を確かめる、など）。
                // ステップは失敗していないので FAILED にせず（TestRecorder が FAILED から最初の失敗を決めるため）、
                // テストの失敗の候補にもしない
                runHost.log("step ${event.provisionalId} cancelled by the caller: ${cancelled.message ?: "cancelled"}")
                finish(run, event, StepStatus.SKIPPED, started, "cancelled by the caller: ${cancelled.message ?: "cancelled"}", null)
            }
            throw cancelled
        } catch (error: Throwable) {
            throw classify(run, event, started, error, hostless)
        } finally {
            if (lane != null) block?.stepEnded(lane, event)
        }
    }

    /**
     * 記録しないステップ（eventually / awaitUntil の試行）。
     *
     * 記録しないだけで、期限の確認・期限での打ち切り・クライアントの生存の確認・レーンの入力の規則・
     * 例外の分類（サーバーの死亡 → serverDied、入力の失敗の後のクライアントの死亡）は記録するステップと同じにする。
     * 本体には仮 id を渡さない（null）。
     */
    private suspend fun <T> quietStep(
        run: TestRun,
        scope: StepScope,
        inputPlayer: String?,
        body: suspend (TestRun?, Long?) -> T,
    ): T {
        run.deadline.check(run.stepCount.toInt())
        val block = scope.block
        val lane = scope.lane
        try {
            return try {
                withTimeout(run.deadline.remaining()) {
                    run.host.checkClientsAlive()
                    if (inputPlayer != null && block != null && lane != null) block.guard.onInput(inputPlayer, lane)
                    body(run, null)
                }
            } catch (timeout: TimeoutCancellationException) {
                if (!run.deadline.isExceeded) throw timeout
                throw HarnessTimeoutException(
                    "the test exceeded its timeout of ${run.deadline.timeout.inWholeSeconds}s during a retried attempt",
                )
            }
        } catch (cancelled: CancellationException) {
            // 取り消しは外側の記録するステップ（eventually など）が扱う
            throw cancelled
        } catch (error: Throwable) {
            throw classifyQuiet(run, error)
        }
    }

    /**
     * 記録しないステップの例外を classify と同じ規則で分類し、副作用（serverDied）だけを起こして投げ直す例外を返す。
     */
    private fun classifyQuiet(run: TestRun, error: Throwable): Throwable {
        val host = run.host
        return when (error) {
            is ClientDiedException, is HarnessTimeoutException -> error
            is ServerUnavailableException -> error.also { host.serverDied(it) }
            else -> {
                val died = host.deadClient() ?: return error
                host.warn("retried attempt: ${died.message} (after: ${StatusMapper.messageOf(error)})")
                died.addSuppressed(error)
                died
            }
        }
    }

    /**
     * host のログ待ちが従うテストの期限。ステップの記録先と同じ規則で決める。
     *
     * 記録しないスコープ（tearDown・onStarted の StepScope(run = null)）では期限を付けない。
     * tearDown は ActiveTests.finish の前に走るので、実行中のテストだけで探すと、期限切れで終わったテストの
     * 残り 0 秒が後片付けの待ちまで即座に打ち切ってしまう。
     */
    fun deadlineOf(host: StepHost): TestDeadline? = resolve(StepScope.currentBlocking(), host)?.deadline

    /** 記録先を決める。スコープのテストがこのサーバーのものならそれ、違えばこのサーバーで実行中のテスト。 */
    private fun resolve(scope: StepScope?, host: StepHost?): TestRun? {
        if (scope != null) {
            // 記録しないスコープ（onStarted など）の中では、実行中のテストがあっても記録しない
            val run = scope.run ?: return null
            if (host == null || run.host === host) return run
        }
        return if (host == null) ActiveTests.all().firstOrNull() else ActiveTests.on(host)
    }

    /** stepStarted を送り、実行中のステップとして覚える。 */
    private fun begin(run: TestRun, scope: StepScope?, on: String?, action: String, label: String): StepEvent {
        val block = scope?.block
        val lane = scope?.lane
        val event = StepEvent(
            provisionalId = run.nextStepId(),
            phase = scope?.phase ?: run.phase,
            fixture = scope?.fixture,
            on = on,
            action = action,
            label = label,
            startedAt = Instant.now(),
            // 別のサーバーのテストに記録するときは、そのテストでのブロック番号を使う
            parallel = if (block != null && lane != null) ParallelInfo(block.indexFor(run), lane) else null,
            repeat = scope?.repeat?.infoFor(run),
        )
        // ログには層とレーンの位置を残す
        val position = event.parallel?.let { ", parallel ${it.block} lane ${it.lane}" }.orEmpty() +
            event.repeat?.let { ", repeat ${it.block} ${it.iteration}/${it.of}" }.orEmpty()
        run.host.log("step ${event.provisionalId} (${event.phase.name.lowercase()}$position): $action on ${on ?: "-"}: $label")
        run.host.observer.stepStarted(run.testId, event)
        if (block != null && lane != null) block.stepStarted(lane, run, event)
        return event
    }

    /** stepFinished を送り、送った出来事を返す。 */
    private fun finish(
        run: TestRun,
        event: StepEvent,
        status: StepStatus,
        startedNanos: Long,
        error: String?,
        screenshot: String?,
    ): StepEvent {
        val finished = event.copy(
            status = status,
            finishedAt = Instant.now(),
            durationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI,
            error = error,
            screenshot = screenshot,
        )
        run.host.observer.stepFinished(run.testId, finished)
        return finished
    }

    /**
     * ステップの失敗を分類して記録し、呼び出し元へ投げ直す例外を返す。
     */
    private fun classify(run: TestRun, event: StepEvent, startedNanos: Long, error: Throwable, hostless: Boolean = false): Throwable {
        val host = run.host
        return when (error) {
            // クライアントの死亡は phase client（プラグインの失敗ではない）
            is ClientDiedException -> error.also { record(run, event, startedNanos, it, it.message.orEmpty()) }
            // サーバーの死亡: ステップを失敗にし、サーバーを dead にして投げ直す（以後のテストは skipped）
            is ServerUnavailableException -> error.also {
                record(run, event, startedNanos, it, it.message.orEmpty())
                // サーバーに属さないステップでは、死んだサーバーの中のステップが既に serverDied を呼んでいる
                if (!hostless) host.serverDied(it)
            }
            // 期限切れは timeout（StatusMapper が HarnessTimeoutException から決める）
            is HarnessTimeoutException -> error.also { record(run, event, startedNanos, it, it.message.orEmpty()) }
            else -> {
                // 入力や撮影の失敗は、クライアントが死んだ結果のことがある。参加者の誰かが死んでいれば
                // （別のレーンのプレイヤーでも）プラグインの失敗ではなくクライアントの死亡として記録する
                val died = host.deadClient()
                if (died != null) {
                    host.warn("step ${event.provisionalId}: ${died.message} (after: ${StatusMapper.messageOf(error)})")
                    died.addSuppressed(error)
                    record(run, event, startedNanos, died, died.message.orEmpty())
                    died
                } else {
                    record(run, event, startedNanos, error, StatusMapper.messageOf(error))
                    error
                }
            }
        }
    }

    /** 失敗したステップを記録し、テストの失敗の候補として覚える。 */
    private fun record(run: TestRun, event: StepEvent, startedNanos: Long, error: Throwable, message: String) {
        run.host.warn("step ${event.provisionalId} failed: $message")
        val failed = finish(run, event, StepStatus.FAILED, startedNanos, message, null)
        run.stepFailed(failed, error)
    }

    /** 末尾の 0 を落とした秒数（1.5、0.25、2）。 */
    fun seconds(duration: Duration): String =
        BigDecimal.valueOf(duration.inWholeNanoseconds, NANO_SCALE).stripTrailingZeros().toPlainString()

    /** ナノ秒 → ミリ秒。 */
    private const val NANOS_PER_MILLI = 1_000_000L

    /** ナノ秒を秒にする BigDecimal の scale。 */
    private const val NANO_SCALE = 9
}
