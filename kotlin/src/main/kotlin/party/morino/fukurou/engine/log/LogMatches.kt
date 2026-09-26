package party.morino.fukurou.engine.log

import party.morino.fukurou.log.LogMatch

// ログの照合の共通処理。LogWaiter（待ち）と WindowedLogView（出てはいけない行）が共有する

/** MULTILINE を足した正規表現（元のオプションは保つ）。 */
internal fun Regex.multiline(): Regex =
    if (RegexOption.MULTILINE in options) this else Regex(pattern, options + RegexOption.MULTILINE)

/**
 * 一致を LogMatch にする。line は一致の始まる行全体、lineNumber はそのファイル内の行番号。
 *
 * @param text 照合したテキスト
 * @param firstLine text の最初の行の行番号
 * @param match 一致
 */
internal fun toLogMatch(text: String, firstLine: Int, match: MatchResult): LogMatch {
    val start = match.range.first
    // 一致の始まる位置を含む行の両端
    val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    // 行番号は text の先頭から一致の前までの改行の数で決まる
    var newlines = 0
    for (i in 0 until lineStart) if (text[i] == '\n') newlines++
    return LogMatch(line = text.substring(lineStart, lineEnd).removeSuffix("\r"), lineNumber = firstLine + newlines, groups = match)
}
