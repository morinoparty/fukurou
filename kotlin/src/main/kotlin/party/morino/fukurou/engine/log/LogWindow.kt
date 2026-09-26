package party.morino.fukurou.engine.log

import party.morino.fukurou.log.LogMark
import party.morino.fukurou.result.model.test.LogRange
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ログファイルの「ある時点から後」だけを増分で読むウィンドウ（runner/log_window.py:24-110）。
 *
 * 最後の改行までしか読まないので、書き込み中の行やマルチバイト文字の途中は次回に回る。
 * inode が変わった・ファイルが短くなったときは先頭から数え直す。複数のレーンから同時に読まれるのでロックで直列にする。
 *
 * @property path 読むログファイル
 */
internal class LogWindow(val path: Path) {
    // ファイルの識別子。クライアントの再起動で latest.log が別ファイルに置き換わったら数え直す
    private var inode: Any? = null

    // 読み終えた位置（改行までしか消費しないので、行の途中で切れることはない）
    private var readPos = LogPosition()

    // 直近の mark() の位置。lineRange() の from はここから決まる
    private var markPos = LogPosition()

    // バッファの先頭の位置（mark() か skip() の位置）。after の切り詰めと行番号の起点に使う
    private var bufferStart = LogPosition()

    // バッファの各行（改行を含み ANSI 除去済み）と、その行が始まるバイトオフセット。after をバイト位置で扱うため行単位で持つ
    private val lines = ArrayList<String>()
    private val lineOffsets = ArrayList<Long>()

    // read() が毎回連結し直さないよう、バッファ全体のテキストも持つ
    private val text = StringBuilder()

    // 複数のスレッドから同時に読まれても、位置とバッファの対応が崩れないようにする
    private val lock = ReentrantLock()

    /** ここから後をウィンドウにする。ファイルが無ければ先頭から。 */
    fun mark(): Unit = lock.withLock {
        catchUp()
        markPos = readPos
        clearBuffer()
    }

    /** mark() から現在までのテキスト（ANSI のカラーコードは除く）。 */
    fun read(): String = lock.withLock {
        catchUp()
        text.toString()
    }

    /**
     * after 以降のテキスト。mark（skip 後はその位置）より前の位置は切り詰める。null なら read() と同じ。
     *
     * after が行の途中を指すときは、その行は含めない（after より後に始まった行だけを返す）。
     */
    fun read(after: LogMark?): String = lock.withLock {
        catchUp()
        if (after == null) return@withLock text.toString()
        // after 以降に始まる最初の行から後を連結する
        val first = firstIndexAtOrAfter(after.offset)
        if (first == 0) text.toString() else lines.subList(first, lines.size).joinToString("")
    }

    /**
     * ここまでの行を read() の対象から外す。lineRange() の始点（mark）は動かさない。
     *
     * ハーネスのリセットのコマンドの応答は logRanges には残したいが、テストの待ちや assertAbsent が
     * それに一致してはいけない。リセットの後にこれを呼び、照合の対象をリセットより後の行だけにする。
     */
    fun skip(): Unit = lock.withLock {
        catchUp()
        clearBuffer()
    }

    /** mark() から現在までに書かれた行の範囲（1 始まり、両端含む）。1 行も無ければ null。 */
    fun lineRange(): LogRange? {
        val (first, last) = lock.withLock {
            catchUp()
            (markPos.newlines + 1) to readPos.newlines
        }
        return if (last < first) null else LogRange(fromLine = first, to = last)
    }

    /** 読み終えた位置（after に渡すための LogMark）。現在の末尾を返すため、先に読み足す。 */
    fun offset(): LogMark = lock.withLock {
        catchUp()
        LogMark(readPos.offset)
    }

    /** after 以降のテキストの最初の行の、ファイル内の行番号（1 始まり）。メッセージの行番号に使う。 */
    fun firstLineNumber(after: LogMark?): Int = lock.withLock {
        catchUp()
        // バッファの先頭の行番号に、after より前にあるバッファの行数を足す
        val skipped = if (after == null) 0 else firstIndexAtOrAfter(after.offset)
        bufferStart.newlines + skipped + 1
    }

    /** offset 以降に始まる最初のバッファの行の添字（無ければ lines.size）。呼び出し側がロックを持つ。 */
    private fun firstIndexAtOrAfter(offset: Long): Int {
        // 行の開始位置は昇順なので二分探索する
        var low = 0
        var high = lineOffsets.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lineOffsets[mid] < offset) low = mid + 1 else high = mid
        }
        return low
    }

    /** 前回の位置から最後の改行までを読み、バッファと行数に足す。呼び出し側がロックを持つ。 */
    private fun catchUp() {
        val data = try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val key = fileKey()
                if (inode != null && (key != inode || channel.size() < readPos.offset)) {
                    // 別のファイルに置き換わった（クライアントの再起動で latest.log がローテートした等）か、短くなった
                    reset()
                }
                inode = key
                readFrom(channel, readPos.offset)
            }
        } catch (_: NoSuchFileException) {
            // まだ作られていない（サーバーやクライアントが起動中）
            return
        }
        // 行の途中（書き込み中の行やマルチバイト文字の途中）は次回に回す
        val cut = data.lastIndexOf(NEWLINE)
        if (cut < 0) return
        // 改行ごとに切り分けて行として足す。不正な UTF-8 は置換文字にする（Python の errors="replace"）
        var start = 0
        var newlines = 0
        for (i in 0..cut) {
            if (data[i] != NEWLINE) continue
            val line = String(data, start, i + 1 - start, Charsets.UTF_8)
            appendLine(readPos.offset + start, Ansi.strip(line))
            start = i + 1
            newlines++
        }
        readPos = LogPosition(readPos.offset + cut + 1, readPos.newlines + newlines)
    }

    /** バッファに 1 行足す。 */
    private fun appendLine(offset: Long, line: String) {
        lines.add(line)
        lineOffsets.add(offset)
        text.append(line)
    }

    /** channel の position 以降をすべて読む。 */
    private fun readFrom(channel: FileChannel, position: Long): ByteArray {
        val size = channel.size()
        if (size <= position) return ByteArray(0)
        // 読んでいる間に伸びた分は次回に回す（最後の改行で切るので、ここで止めても問題ない）
        val buffer = ByteBuffer.allocate(Math.toIntExact(size - position))
        var at = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) break
            at += n
        }
        return buffer.array().copyOf(buffer.position())
    }

    /** ファイルの識別子。Linux では inode（unix:ino）、使えなければ fileKey。 */
    private fun fileKey(): Any? = try {
        Files.getAttribute(path, "unix:ino")
    } catch (_: UnsupportedOperationException) {
        Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
    } catch (_: IllegalArgumentException) {
        Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
    }

    /** バッファを空にし、その先頭を現在の読み取り位置にする。 */
    private fun clearBuffer() {
        bufferStart = readPos
        lines.clear()
        lineOffsets.clear()
        text.setLength(0)
    }

    /** 先頭から数え直す（log_window.py _reset）。 */
    private fun reset() {
        readPos = LogPosition()
        markPos = LogPosition()
        clearBuffer()
    }

    private companion object {
        // 行の区切り（UTF-8 のマルチバイト文字の途中に 0x0A は現れない）
        const val NEWLINE: Byte = 0x0A
    }
}
