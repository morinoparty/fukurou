package party.morino.fukurou.engine.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.result.model.test.LogRange
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class LogWindowTest {
    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("latest.log")

    private fun append(bytes: ByteArray) {
        Files.write(log, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun append(text: String) = append(text.toByteArray())

    @Test
    @DisplayName("A missing file reads as empty")
    fun missingFile() {
        val window = LogWindow(log)
        window.mark()
        assertEquals("", window.read())
        assertNull(window.lineRange())
    }

    @Test
    @DisplayName("A partial line waits for its newline and ANSI codes are stripped")
    fun partialLine() {
        val window = LogWindow(log)
        window.mark()
        append("\u001B[32mdone\u001B[0m\npart")
        assertEquals("done\n", window.read())
        append("ial\n")
        assertEquals("done\npartial\n", window.read())
        assertEquals(LogRange(1, 2), window.lineRange())
    }

    @Test
    @DisplayName("A split UTF-8 character is read once complete")
    fun splitUtf8() {
        val window = LogWindow(log)
        window.mark()
        val bytes = "あ\n".toByteArray()
        append(bytes.copyOfRange(0, 1))
        assertEquals("", window.read())
        assertNull(window.lineRange())
        append(bytes.copyOfRange(1, bytes.size))
        assertEquals("あ\n", window.read())
    }

    @Test
    @DisplayName("Mark, skip and lineRange follow the Python window")
    fun markSkipLineRange() {
        append("before\n")
        val window = LogWindow(log)
        window.mark()
        append("reset\n")
        window.skip()
        append("test\n")
        // skip は照合の対象から外すだけで、行の範囲の始点は mark のまま
        assertEquals("test\n", window.read())
        assertEquals(LogRange(2, 3), window.lineRange())
        // 照合の対象の最初の行はリセットの後の行
        assertEquals(3, window.firstLineNumber(null))
    }

    @Test
    @DisplayName("Read after a mark returns only later lines and clamps earlier marks")
    fun afterClamping() {
        append("old\n")
        val window = LogWindow(log)
        val early = window.offset()
        window.mark()
        append("one\n")
        val middle = window.offset()
        append("two\n")
        assertEquals("two\n", window.read(middle))
        assertEquals(3, window.firstLineNumber(middle))
        // mark より前の位置は mark に切り詰める
        assertEquals("one\ntwo\n", window.read(early))
        assertEquals("one\ntwo\n", window.read(LogMark(0)))
        assertEquals(2, window.firstLineNumber(early))
    }

    @Test
    @DisplayName("A rotated file (new inode) is read from the start")
    fun inodeRotation() {
        append("first\nsecond\n")
        val window = LogWindow(log)
        window.mark()
        // 古いファイルが残っている間に別ファイルを作って置き換え、inode が必ず変わるようにする
        val replacement = dir.resolve("next.log")
        Files.writeString(replacement, "new first\nnew second\nnew third\n")
        Files.move(replacement, log, StandardCopyOption.REPLACE_EXISTING)
        assertEquals("new first\nnew second\nnew third\n", window.read())
        assertEquals(LogRange(1, 3), window.lineRange())
    }

    @Test
    @DisplayName("A truncated file is read from the start")
    fun truncation() {
        append("a long first line\nsecond\n")
        val window = LogWindow(log)
        window.mark()
        Files.writeString(log, "short\n")
        assertEquals("short\n", window.read())
        assertEquals(LogRange(1, 1), window.lineRange())
    }
}
