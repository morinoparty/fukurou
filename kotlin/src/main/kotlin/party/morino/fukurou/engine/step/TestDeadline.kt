package party.morino.fukurou.engine.step

import party.morino.fukurou.error.HarnessTimeoutException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * テスト 1 件のソフトデッドライン（開始 + timeout）。すべての待ちは remaining() で上限を切る。
 *
 * @property timeout テストの期限
 * @property startedAt 開始時刻（単調時計）
 */
internal class TestDeadline(
    val timeout: Duration,
    private val startedAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
) {
    /** 残り時間。過ぎていれば 0。 */
    fun remaining(): Duration = (timeout - startedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)

    /** 期限を過ぎたか。 */
    val isExceeded: Boolean get() = startedAt.elapsedNow() >= timeout

    /** 期限を過ぎていれば「ステップ index の前に期限を超えた」HarnessTimeoutException。 */
    fun check(stepIndex: Int) {
        if (isExceeded) {
            throw HarnessTimeoutException("the test exceeded its timeout of ${timeout.inWholeSeconds}s before step $stepIndex")
        }
    }
}
