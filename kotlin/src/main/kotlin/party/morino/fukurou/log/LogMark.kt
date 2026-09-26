package party.morino.fukurou.log

/**
 * ログファイルの位置（バイトオフセット）。await / assertAbsent の after に渡す。
 *
 * @property offset ファイル先頭からのバイト数
 */
@JvmInline
public value class LogMark(public val offset: Long)
