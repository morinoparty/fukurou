package party.morino.fukurou.engine.step

import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.TestRun
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 流れの補助（step / repeat / eventually / awaitUntil、v3 §2.3）の実装。
 *
 * 記録先のテストは pause と同じく StepScope、無ければ実行中のテスト（ActiveTests）から決める。
 * サーバーに属さないステップなので on は null。
 */
internal object FlowRunner {
    /** repeat の回数の上限。 */
    const val MAX_REPEAT: Int = 1000

    /**
     * 任意の名前のステップ（action "step"）。中のステップも記録する（外側が先に添字を取る）。
     * スコープが無ければ pause と同じく実行中のテストすべてに記録する。
     */
    suspend fun <T> step(label: String, block: suspend () -> T): T = StepRunner.hostless(ACTION_STEP, label, block)

    /**
     * block を times 回実行し、中のステップに RepeatInfo を付ける。block には 0 始まりの回数を渡す
     * （kotlin.repeat と同じ）。RepeatInfo.iteration は契約どおり 1 始まり。times が 0 なら何もしない
     * （kotlin.repeat と同じ。ブロック番号も取らない）。
     *
     * @throws IllegalArgumentException times が 0〜1000 の外
     * @throws IllegalStateException repeat の中で repeat を呼んだ
     */
    suspend fun repeat(times: Int, block: suspend (index: Int) -> Unit) {
        require(times in 0..MAX_REPEAT) { "repeat needs 0..$MAX_REPEAT iterations, got $times" }
        val outer = StepScope.current()
        // 入れ子の RepeatInfo の一覧（外側から順）は契約上表せるが、v3 では 1 段に限る
        check(outer?.repeat == null) { "repeat cannot be nested (already inside repeat block ${outer?.repeat?.block?.index})" }
        if (times == 0) return
        val base = baseScope(outer)
        val run = base.run
        val repeatBlock = RepeatBlock(run?.nextRepeatBlock() ?: 0, run, times)
        for (index in 0 until times) {
            withContext(base.copy(repeat = RepeatFrame(repeatBlock, index + 1))) { block(index) }
        }
    }

    /**
     * block が AssertionError を投げる間、interval ごとに再試行する（action "eventually"、label は "10s"）。
     *
     * 試行の中のステップは記録しない（StepScope.quiet）。AssertionError 以外（fukurou の例外を含む）は再試行せずに投げる。
     * 1 回の試行も timeout の残りで打ち切る。テストの期限が先に来れば、外側のステップが HarnessTimeoutException にする。
     *
     * @throws AssertionError timeout までに成功しなかった（最後の AssertionError。試行の回数を suppressed に足す）
     */
    suspend fun <T> eventually(timeout: Duration, interval: Duration, block: suspend () -> T): T {
        checkTiming(timeout, interval)
        val label = "${StepRunner.seconds(timeout)}s"
        return StepRunner.anchored(ACTION_EVENTUALLY, label) { anchors ->
            val host = baseScope(StepScope.current()).run?.host
            retry(anchors, timeout, interval) {
                try {
                    Attempt.Done(block())
                } catch (failed: AssertionError) {
                    // LogAssertionError・CommandFailedError・EventAssertionError もここに入る
                    Attempt.Retry(failed)
                }
            }.getOrElse { attempts, last ->
                val summary = "eventually gave up after $attempts attempt${if (attempts == 1) "" else "s"} in $label"
                host?.warn(summary)
                if (last != null) {
                    // スタックトレースの復元で写されると suppressed は写しに載らないので、元の例外にも付ける
                    val note = AssertionError(summary)
                    recoveryOriginals(last).forEach { it.addSuppressed(note) }
                    last
                } else {
                    AssertionError("$summary: the attempt did not finish in time")
                }
            }
        }
    }

    /**
     * condition が true を返すまで interval ごとに確かめる（action "await_until"、label は description）。
     *
     * condition の例外は再試行せずに投げる。試行の中のステップは記録しない。
     *
     * @throws AssertionError timeout までに true にならなかった
     */
    suspend fun awaitUntil(description: String, timeout: Duration, interval: Duration, condition: suspend () -> Boolean) {
        checkTiming(timeout, interval)
        StepRunner.anchored(ACTION_AWAIT_UNTIL, description) { anchors ->
            retry(anchors, timeout, interval) {
                if (condition()) Attempt.Done(Unit) else Attempt.Retry(null)
            }.getOrElse { _, _ ->
                AssertionError("timed out after ${StepRunner.seconds(timeout)}s waiting until $description")
            }
        }
    }

    /** 1 回の試行の結果。 */
    private sealed interface Attempt<out T> {
        /** 成功した。 */
        class Done<T>(val value: T) : Attempt<T>

        /** もう一度試す。error は最後の失敗（awaitUntil の false なら null）。 */
        class Retry(val error: AssertionError?) : Attempt<Nothing>
    }

    /** retry の結末。 */
    private sealed interface Outcome<out T> {
        /** 成功した。 */
        class Success<T>(val value: T) : Outcome<T>

        /** timeout までに成功しなかった。 */
        class GaveUp(val attempts: Int, val last: AssertionError?) : Outcome<Nothing>

        /** 成功の値を返すか、あきらめたときの例外を投げる。 */
        fun getOrElse(error: (attempts: Int, last: AssertionError?) -> Throwable): T =
            when (this) {
                is Success -> value
                is GaveUp -> throw error(attempts, last)
            }
    }

    /**
     * 試行を timeout まで繰り返す。試行は quiet のスコープで走らせ、timeout の残りで打ち切る。
     *
     * テストの期限による打ち切り（呼び出し元の StepRunner の withTimeout）はここでは捕まえない。
     *
     * @param anchors 記録先のテスト → このブロックのステップの仮 id（試行の中のスクリーンショットを結びつける）
     */
    private suspend fun <T> retry(
        anchors: Map<TestRun, Long>,
        timeout: Duration,
        interval: Duration,
        attempt: suspend () -> Attempt<T>,
    ): Outcome<T> {
        val base = baseScope(StepScope.current())
        // 入れ子の eventually は外側のブロックの試行の一部なので、外側の QuietBlock をそのまま使う
        val quiet = if (base.quiet) base else base.copy(quiet = true, quietBlock = QuietBlock(anchors))
        val started = TimeSource.Monotonic.markNow()
        var attempts = 0
        var last: AssertionError? = null
        while (true) {
            val remaining = timeout - started.elapsedNow()
            if (!remaining.isPositive()) return Outcome.GaveUp(attempts, last)
            attempts++
            // 自分の withTimeoutOrNull が切れたときだけ null になる（中の withTimeout の切れは例外のまま伝わる）
            val result = withTimeoutOrNull(remaining) { withContext(quiet) { attempt() } }
                ?: return Outcome.GaveUp(attempts, last)
            when (result) {
                is Attempt.Done -> return Outcome.Success(result.value)
                is Attempt.Retry -> result.error?.let { last = it }
            }
            val left = timeout - started.elapsedNow()
            if (!left.isPositive()) return Outcome.GaveUp(attempts, last)
            delay(minOf(interval, left))
        }
    }

    /** error と、コルーチンのスタックトレースの復元で写す前の元の例外（同じクラス・同じメッセージの cause）。 */
    private fun recoveryOriginals(error: Throwable): List<Throwable> =
        generateSequence(error) { current ->
            current.cause?.takeIf { it !== current && it.javaClass == current.javaClass && it.message == current.message }
        }.take(MAX_RECOVERY_DEPTH).toList()

    /**
     * 記録先のスコープ。今のスコープ、無ければ実行中のテストのスコープ（ParallelRunner と同じ）。
     * 作ったスコープは implicit にし、中のサーバーに属さないステップを実行中のテストすべてに記録させる。
     */
    private fun baseScope(outer: StepScope?): StepScope = outer ?: StepScope(ActiveTests.all().firstOrNull(), implicit = true)

    /** timeout と interval の検査。 */
    private fun checkTiming(timeout: Duration, interval: Duration) {
        require(timeout.isPositive()) { "timeout must be positive, got $timeout" }
        require(!interval.isNegative()) { "interval must not be negative, got $interval" }
    }

    /** 写しをたどる段数の上限。 */
    private const val MAX_RECOVERY_DEPTH = 8

    /** step のアクション名。 */
    private const val ACTION_STEP = "step"

    /** eventually のアクション名。 */
    private const val ACTION_EVENTUALLY = "eventually"

    /** awaitUntil のアクション名。 */
    private const val ACTION_AWAIT_UNTIL = "await_until"
}
