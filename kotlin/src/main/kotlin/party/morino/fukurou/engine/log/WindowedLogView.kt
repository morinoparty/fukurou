package party.morino.fukurou.engine.log

import party.morino.fukurou.engine.step.TestDeadline
import party.morino.fukurou.log.LogAssertionError
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
    override fun mark(): LogMark = window().offset()

    override suspend fun await(pattern: Regex, timeout: Duration, after: LogMark?): LogMatch =
        LogWaiter.await(window(), pattern, timeout, after, source, artifactPath(), liveness, deadline())

    override fun assertAbsent(pattern: Regex, after: LogMark?) {
        val current = window()
        val text = current.read(after)
        val firstLine = current.firstLineNumber(after)
        // Python は最初の一致だけを報告していたが、一致した行をすべて（行ごとに 1 回）挙げる
        val matches = pattern.multiline().findAll(text)
            .map { toLogMatch(text, firstLine, it) }
            .distinctBy { it.lineNumber }
            .map { it.lineNumber to it.line }
            .toList()
        if (matches.isEmpty()) return
        throw LogAssertionError(absentMessage(source, pattern, artifactPath(), matches, after))
    }

    override fun text(after: LogMark?): String = window().read(after)
}
