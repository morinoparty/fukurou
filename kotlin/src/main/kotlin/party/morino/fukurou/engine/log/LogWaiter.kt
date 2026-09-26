package party.morino.fukurou.engine.log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.fukurou.engine.step.TestDeadline
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.log.LogAssertionError
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** ログに pattern が出るまで 0.5 s ごとに見る（runner/scenario_runner.py:159-182）。 */
internal object LogWaiter {
    /** 読み足す間隔（scenario_runner.py LOG_POLL_SECONDS）。 */
    val POLL_INTERVAL: Duration = 500.milliseconds

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
     * @param io ファイルを読むディスパッチャ（テストでは仮想時間を乱さないよう差し替える）
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
        io: CoroutineContext = Dispatchers.IO,
    ): LogMatch {
        // Python と同じく複数行のテキストの中で ^ $ を行ごとに効かせる
        val compiled = pattern.multiline()
        // 上限は最初に一度だけ決める。待ちは仮想時間でも進む withTimeoutOrNull で切る
        val remaining = deadline?.remaining()
        val deadlineWins = remaining != null && remaining < timeout
        val limit = if (remaining != null && deadlineWins) remaining else timeout
        val found = withTimeoutOrNull(limit) {
            var match = poll(window, compiled, after, io)
            while (match == null) {
                // 待っている間にサーバーやクライアントが落ちたら、時間切れを待たずにすぐ失敗させる
                liveness()
                delay(POLL_INTERVAL)
                match = poll(window, compiled, after, io)
            }
            match
        }
        if (found != null) return found
        // 最後の待ちの間に出た行を取りこぼさないよう、打ち切る前にもう一度だけ読む
        poll(window, compiled, after, io)?.let { return it }
        if (deadlineWins) {
            // 待ちの時間ではなくテストの期限が先に尽きた。プラグインの誤りではなくハーネスの時間切れとして扱う
            throw HarnessTimeoutException(
                "the test exceeded its timeout of ${displayDuration(deadline.timeout)} " +
                    "while waiting for $source to match ${displayPattern(pattern)}",
            )
        }
        // メッセージには時間切れの時点のウィンドウを載せる
        val (text, firstLine) = snapshot(window, after, io)
        throw LogAssertionError(timeoutMessage(source, pattern, timeout, artifactPath, text, firstLine, after))
    }

    /**
     * 1 回読み足して照合する。一致すれば、一致の始まる行とその行番号を返す。
     *
     * @return 一致しなければ null
     */
    private suspend fun poll(window: LogWindow, compiled: Regex, after: LogMark?, io: CoroutineContext): LogMatch? {
        val (text, firstLine) = snapshot(window, after, io)
        val match = compiled.find(text) ?: return null
        return toLogMatch(text, firstLine, match)
    }

    /** ファイルの読み取りは I/O スレッドで行い、テストのタイムアウトの割り込みが届くようにする（§4 規則 1）。 */
    private suspend fun snapshot(window: LogWindow, after: LogMark?, io: CoroutineContext): Pair<String, Int> =
        runInterruptible(io) { window.read(after) to window.firstLineNumber(after) }
}
