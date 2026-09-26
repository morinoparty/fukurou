package party.morino.fukurou.engine.log

import party.morino.fukurou.log.LogMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// §1.10 の失敗メッセージの組み立て。LogWaiter（時間切れ）と WindowedLogView（出てはいけない行）が共有する

/** 時間切れのメッセージに出す末尾の行数。 */
internal const val TAIL_LINES: Int = 8

/** assertAbsent のメッセージに出す一致行の上限。 */
internal const val MAX_REPORTED_LINES: Int = 8

/**
 * パターンを `/…/` の形で表示する。区切りと紛れないよう、エスケープされていない / は \/ にする。
 *
 * @param pattern 表示する正規表現
 * @return `/pattern/`
 */
internal fun displayPattern(pattern: Regex): String {
    val out = StringBuilder("/")
    var escaped = false
    for (c in pattern.pattern) {
        // 既に \/ と書かれていれば二重にしない
        if (c == '/' && !escaped) out.append('\\')
        out.append(c)
        escaped = c == '\\' && !escaped
    }
    return out.append('/').toString()
}

/**
 * 時間を `10s` の形で表示する（Python の `{timeout:g}s`）。秒で割り切れないときは Duration の表記。
 *
 * @param duration 表示する時間
 */
internal fun displayDuration(duration: Duration): String =
    if (duration == duration.inWholeSeconds.seconds) "${duration.inWholeSeconds}s" else duration.toString()

/**
 * 照合の対象の始まりの説明。after を渡したときはテスト開始ではなく、その位置から。
 *
 * @param after 照合の始点
 */
internal fun sincePhrase(after: LogMark?): String =
    if (after == null) "since the test started" else "since offset ${after.offset}"

/**
 * `path:from-to` または `path:n`（1 行だけのとき）。
 *
 * @param artifactPath run ディレクトリからのパス
 * @param from 最初の行番号
 * @param to 最後の行番号
 */
internal fun lineLocation(artifactPath: String, from: Int, to: Int): String =
    if (from == to) "$artifactPath:$from" else "$artifactPath:$from-$to"

/**
 * テキストを改行で行に分ける。最後の改行の後の空文字列は行に数えない。
 *
 * @param text ウィンドウのテキスト（行は改行で終わる）
 */
internal fun splitLines(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }

/**
 * 待ちが時間切れになったときのメッセージ。
 *
 * ```
 * Timed out after 10s waiting for the server log (paper-26.3-stamp-arena) to match
 *   /Alice issued server command: \/st :thinking-face:/
 * Last 8 lines since the test started (logs/sessions/0/server.log:1204-1211):
 *   [12:00:01 INFO]: Alice issued server command: /st :thinking_face:
 * Hint: 1 line is a near miss (differs only in '_' vs '-').
 * ```
 *
 * @param source ログの説明
 * @param pattern 待っていた正規表現
 * @param timeout 待った時間
 * @param artifactPath run ディレクトリからのパス
 * @param text 照合したテキスト
 * @param firstLine text の最初の行の行番号
 * @param after 照合の始点
 */
internal fun timeoutMessage(
    source: String,
    pattern: Regex,
    timeout: Duration,
    artifactPath: String,
    text: String,
    firstLine: Int,
    after: LogMark?,
): String {
    val lines = splitLines(text)
    val out = StringBuilder()
    out.append("Timed out after ${displayDuration(timeout)} waiting for $source to match\n")
    out.append("  ${displayPattern(pattern)}\n")
    if (lines.isEmpty()) {
        // 1 行も出ていないことが、そのまま原因の手がかりになる
        out.append("No lines ${sincePhrase(after)} ($artifactPath).")
    } else {
        // 末尾の 8 行と、そのファイル内の行番号
        val tail = lines.takeLast(TAIL_LINES)
        val from = firstLine + lines.size - tail.size
        val to = firstLine + lines.size - 1
        val count = if (tail.size == 1) "Last line" else "Last ${tail.size} lines"
        out.append("$count ${sincePhrase(after)} (${lineLocation(artifactPath, from, to)}):")
        tail.forEach { out.append("\n  ").append(it) }
    }
    // 惜しい行はウィンドウ全体から探す（末尾 8 行より前にあることも多い）
    NearMiss.hint(pattern, lines)?.let { out.append('\n').append(it) }
    return out.toString()
}

/**
 * 出てはいけない行が出たときのメッセージ。
 *
 * ```
 * Alice's client log has 1 line matching
 *   /\[CHAT\].*(Unknown( or incomplete)? command|…)/
 * since the test started (logs/sessions/0/clients/Alice.log:311):
 *   [Render thread/INFO]: [CHAT] Stamp not found: thinking-face
 * ```
 *
 * @param source ログの説明
 * @param pattern 照合した正規表現
 * @param artifactPath run ディレクトリからのパス
 * @param matches 一致した行（行番号と行）
 * @param after 照合の始点
 */
internal fun absentMessage(
    source: String,
    pattern: Regex,
    artifactPath: String,
    matches: List<Pair<Int, String>>,
    after: LogMark?,
): String {
    // "the server log (…)" を文頭に置くので先頭を大文字にする
    val subject = source.replaceFirstChar { it.uppercaseChar() }
    val count = if (matches.size == 1) "1 line" else "${matches.size} lines"
    val shown = matches.take(MAX_REPORTED_LINES)
    val numbers = shown.joinToString(",") { it.first.toString() }
    val out = StringBuilder()
    out.append("$subject has $count matching\n")
    out.append("  ${displayPattern(pattern)}\n")
    out.append("${sincePhrase(after)} ($artifactPath:$numbers):")
    shown.forEach { out.append("\n  ").append(it.second) }
    if (matches.size > shown.size) out.append("\n  … and ${matches.size - shown.size} more")
    return out.toString()
}
