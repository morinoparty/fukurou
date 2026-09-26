package party.morino.fukurou.engine.log

import party.morino.fukurou.log.LogMark
import party.morino.fukurou.result.model.test.LogRange
import java.nio.file.Path

/**
 * ログファイルの「ある時点から後」だけを増分で読むウィンドウ（runner/log_window.py:24-110）。
 *
 * 最後の改行までしか読まないので、書き込み中の行やマルチバイト文字の途中は次回に回る。
 * inode が変わった・ファイルが短くなったときは先頭から数え直す。複数のレーンから同時に読まれるのでロックで直列にする。
 *
 * @property path 読むログファイル
 */
internal class LogWindow(val path: Path) {
    /** ここから後をウィンドウにする。ファイルが無ければ先頭から。 */
    fun mark(): Unit = TODO("WP5: LogWindow.mark($path)")

    /** mark() から現在までのテキスト（ANSI のカラーコードは除く）。 */
    fun read(): String = TODO("WP5: LogWindow.read")

    /** after 以降のテキスト。mark より前の位置は mark に切り詰める。null なら read() と同じ。 */
    fun read(after: LogMark?): String = TODO("WP5: LogWindow.read($after)")

    /** ここまでの行を read() の対象から外す。lineRange() の始点（mark）は動かさない。 */
    fun skip(): Unit = TODO("WP5: LogWindow.skip")

    /** mark() から現在までに書かれた行の範囲（1 始まり、両端含む）。1 行も無ければ null。 */
    fun lineRange(): LogRange? = TODO("WP5: LogWindow.lineRange")

    /** 読み終えた位置（after に渡すための LogMark）。 */
    fun offset(): LogMark = TODO("WP5: LogWindow.offset")

    /** after 以降のテキストの最初の行の、ファイル内の行番号（1 始まり）。メッセージの行番号に使う。 */
    fun firstLineNumber(after: LogMark?): Int = TODO("WP5: LogWindow.firstLineNumber($after)")
}
