package party.morino.fukurou.engine.log

/**
 * ファイル内の位置。offset バイト目までに newlines 個の改行がある（log_window.py _Position）。
 *
 * @property offset ファイル先頭からのバイト数
 * @property newlines offset までの改行の数
 */
internal data class LogPosition(val offset: Long = 0, val newlines: Int = 0)
