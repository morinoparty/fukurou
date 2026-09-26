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
import java.time.Instant
import kotlin.time.Duration

/**
 * 1 ステップを実行して記録する（run/step_executor.py:60-128 StepExecutor.run の移植）。
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
     * @param action Python と同じアクション名
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
        return stepIn(run, scope, host, on, action, label, inputPlayer, screenshotOf, body)
    }

    /**
     * 記録される待ち（action "wait"、on null、label "1.5s"）。テストの残り時間で打ち切る（runner/cancellation.py:17）。
     *
     * StepScope が無く実行中のテストが複数ある（1 つのテストを 2 台のサーバーで実行している）ときは、全部に記録する。
     */
    suspend fun pause(duration: Duration) {
        val scope = StepScope.current()
        // スコープがあればその記録先だけ。無ければ実行中のテストすべて（§1.8）
        val runs = if (scope != null) listOfNotNull(scope.run) else ActiveTests.all()
        val label = "${seconds(duration)}s"
        // 記録先ごとに入れ子のステップにし、待ち自体は 1 回だけにする
        val wait: suspend () -> Unit = { delay(duration) }
        val nested = runs.fold(wait) { inner, run ->
            { stepIn(run, scope, run.host, null, "wait", label, null, { null }) { _, _ -> inner() } }
        }
        nested()
    }

    /**
     * suspend でない呼び出し（assertNoLog / assertNoChat）のステップ。待ちが無いので期限の打ち切りはしない。
     */
    fun <T> instant(host: StepHost?, on: String?, action: String, label: String, body: () -> T): T {
        val scope = StepScope.currentBlocking()
        val run = resolve(scope, host) ?: return body().also { host?.log("step outside a test: $action on ${on ?: "-"}: $label") }
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
        body: suspend (TestRun?, Long?) -> T,
    ): T {
        if (run == null) {
            // テストの外（サーバーの起動直後など）。result.json には載せず、何をしたかだけ残す
            host?.log("step outside a test: $action on ${on ?: "-"}: $label")
            return body(null, null)
        }
        val runHost = run.host
        // 期限を過ぎていれば、ステップを始めずにテストを timeout にする（suite_run.py:403-406）
        run.deadline.check(run.stepCount.toInt())
        val event = begin(run, scope, on, action, label)
        val block = scope?.block
        val lane = scope?.lane
        val started = System.nanoTime()
        try {
            val result = try {
                // ステップ 1 つもテストの残り時間を超えて待たない（どの待ちも期限で打ち切る、§4.11）
                withTimeout(run.deadline.remaining()) {
                    // ステップの前に全員の生存を確かめる（scenario_runner.py:114）。死んでいれば client の失敗として記録される
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
            // ステップ自身の失敗ではないので死亡の判定はしない（step_executor.py:95）
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
            throw classify(run, event, started, error)
        } finally {
            if (lane != null) block?.stepEnded(lane, event)
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
            parallel = if (block != null && lane != null) ParallelInfo(block.index, lane) else null,
        )
        // ログには Python と同じく層とレーンの位置を残す
        val position = event.parallel?.let { ", parallel ${it.block} lane ${it.lane}" }.orEmpty()
        run.host.log("step ${event.provisionalId} (${event.phase.name.lowercase()}$position): $action on ${on ?: "-"}: $label")
        run.host.observer.stepStarted(run.testId, event)
        if (block != null && lane != null) block.stepStarted(lane, event)
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
     * ステップの失敗を分類して記録し、呼び出し元へ投げ直す例外を返す（step_executor.py:95-128）。
     */
    private fun classify(run: TestRun, event: StepEvent, startedNanos: Long, error: Throwable): Throwable {
        val host = run.host
        return when (error) {
            // クライアントの死亡は phase client（プラグインの失敗ではない）
            is ClientDiedException -> error.also { record(run, event, startedNanos, it, it.message.orEmpty()) }
            // サーバーの死亡: ステップを失敗にし、サーバーを dead にして投げ直す（以後のテストは skipped）
            is ServerUnavailableException -> error.also {
                record(run, event, startedNanos, it, it.message.orEmpty())
                host.serverDied(it)
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

    /** Python の %g と同じく、末尾の 0 を落とした秒数（1.5、0.25、2）。 */
    fun seconds(duration: Duration): String =
        BigDecimal.valueOf(duration.inWholeNanoseconds, NANO_SCALE).stripTrailingZeros().toPlainString()

    /** ナノ秒 → ミリ秒。 */
    private const val NANOS_PER_MILLI = 1_000_000L

    /** ナノ秒を秒にする BigDecimal の scale。 */
    private const val NANO_SCALE = 9
}
