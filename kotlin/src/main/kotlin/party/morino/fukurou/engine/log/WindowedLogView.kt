package party.morino.fukurou.engine.log

import party.morino.fukurou.engine.step.TestDeadline
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import party.morino.fukurou.log.LogView
import kotlin.time.Duration

/**
 * LogView の実装。クライアントの再起動でウィンドウが差し替わるので、window は毎回 provider から取る。
 *
 * @property window 現在のウィンドウ
 * @property source メッセージに出すログの説明
 * @property artifactPath 現在のウィンドウの run ディレクトリからのパス
 * @property liveness 待ちの間の生存確認
 * @property deadline 実行中のテストのデッドライン（テストの外では null）
 */
internal class WindowedLogView(
    private val window: () -> LogWindow,
    private val source: String,
    private val artifactPath: () -> String,
    private val liveness: () -> Unit,
    private val deadline: () -> TestDeadline?,
) : LogView {
    override fun mark(): LogMark = TODO("WP5: WindowedLogView.mark($window, $source, $artifactPath)")

    override suspend fun await(pattern: Regex, timeout: Duration, after: LogMark?): LogMatch =
        TODO("WP5: WindowedLogView.await($pattern, $timeout, $after, $liveness, $deadline)")

    override fun assertAbsent(pattern: Regex, after: LogMark?): Unit = TODO("WP5: WindowedLogView.assertAbsent($pattern, $after)")

    override fun text(after: LogMark?): String = TODO("WP5: WindowedLogView.text($after)")
}
