package party.morino.fukurou.log

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** サーバーのコンソールやクライアントの latest.log を、テストごとの窓で見るビュー。 */
public interface LogView {
    /** 現在の末尾（バイトオフセット）。after に渡すと、それ以降に書かれた行だけを見る。 */
    public fun mark(): LogMark

    /** テスト開始（リセット後）以降、または after 以降に pattern（MULTILINE）が出るまで待つ。 */
    public suspend fun await(pattern: Regex, timeout: Duration = 60.seconds, after: LogMark? = null): LogMatch

    /** 一致する行があれば LogAssertionError。 */
    public fun assertAbsent(pattern: Regex, after: LogMark? = null)

    /** テスト開始以降のテキスト（ANSI 除去済み）。 */
    public fun text(after: LogMark? = null): String
}
