package party.morino.fukurou.engine.log

import party.morino.fukurou.engine.step.TestDeadline
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import kotlin.time.Duration

/** ログに pattern が出るまで 0.5 s ごとに見る（runner/scenario_runner.py:159-182）。 */
internal object LogWaiter {
    /**
     * window の after 以降（null ならウィンドウ全体）に pattern（MULTILINE）が出るまで待つ。
     *
     * 待つ前に毎回 liveness() を呼ぶ（ClientDiedException / ServerUnavailableException を投げる）。
     * 実際の上限は min(timeout, deadline.remaining())。時間切れは §1.10 の書式の LogAssertionError、
     * デッドラインが先に尽きたときは HarnessTimeoutException。
     *
     * @param window 見るウィンドウ
     * @param pattern 照合する正規表現
     * @param timeout 待つ時間
     * @param after この位置以降だけを見る
     * @param source メッセージに出すログの説明（"the server log (paper-26.3-stamp-arena)"、"Alice's client log"）
     * @param artifactPath メッセージに出す run ディレクトリからのパス（logs/sessions/0/server.log）
     * @param liveness サーバーとクライアントの生存確認
     * @param deadline テストのデッドライン。テストの外では null
     */
    suspend fun await(
        window: LogWindow,
        pattern: Regex,
        timeout: Duration,
        after: LogMark?,
        source: String,
        artifactPath: String,
        liveness: () -> Unit,
        deadline: TestDeadline?,
    ): LogMatch = TODO("WP5: LogWaiter.await($window, $pattern, $timeout, $after, $source, $artifactPath, $liveness, $deadline)")
}
