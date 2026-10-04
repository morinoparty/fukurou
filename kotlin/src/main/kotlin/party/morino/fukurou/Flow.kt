package party.morino.fukurou

import party.morino.fukurou.engine.step.FlowRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 任意の名前のステップ（action "step"、on null）として block を記録する。中のステップもそれぞれ記録する
 * （外側のステップが先に添字を取る）。記録先は pause と同じく、今のスコープか実行中のテスト。
 */
public suspend fun <T> step(label: String, block: suspend () -> T): T = FlowRunner.step(label, block)

/**
 * block を times 回（0〜1000。0 なら何もしない）実行し、中のステップに RepeatInfo(block, iteration, of) を付ける。
 * 入れ子にはできない。
 *
 * block の引数は 0 始まりの回数（kotlin.repeat と同じ）。result.json の iteration は 1 始まり。
 * parallel のレーンの中でも、repeat の中で parallel を使っても記録される。
 *
 * `import party.morino.fukurou.*` で取り込むと、スター import が既定の import より優先されるので、そのファイルの
 * 修飾しない repeat はすべてこの関数になる（kotlin.repeat ではなくなる）。ただの繰り返しには kotlin.repeat と書く。
 *
 * @throws IllegalArgumentException times が 0〜1000 の外
 * @throws IllegalStateException repeat の中で repeat を呼んだ
 */
public suspend fun repeat(times: Int, block: suspend (index: Int) -> Unit): Unit = FlowRunner.repeat(times, block)

/**
 * block が AssertionError を投げる間、interval ごとに再試行する（action "eventually"）。timeout で最後の AssertionError を投げる。
 * 中の試行のステップは記録しない。fukurou の例外（サーバー・クライアントの死亡、テストの期限）は再試行しない。
 */
public suspend fun <T> eventually(
    timeout: Duration = 10.seconds,
    interval: Duration = 500.milliseconds,
    block: suspend () -> T,
): T = FlowRunner.eventually(timeout, interval, block)

/**
 * condition が true を返すまで interval ごとに確かめる（action "await_until"、label は description）。
 * timeout までに true にならなければ AssertionError。中の試行のステップは記録しない。
 */
public suspend fun awaitUntil(
    description: String,
    timeout: Duration = 30.seconds,
    interval: Duration = 500.milliseconds,
    condition: suspend () -> Boolean,
): Unit = FlowRunner.awaitUntil(description, timeout, interval, condition)
