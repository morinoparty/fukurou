package party.morino.fukurou.log

/**
 * ログの照合で一致した行。
 *
 * @property line 一致した行（ANSI 除去済み）
 * @property lineNumber ファイル内の行番号（1 始まり）
 * @property groups 正規表現の一致（グループを取り出すため）
 */
public data class LogMatch(val line: String, val lineNumber: Int, val groups: MatchResult)
