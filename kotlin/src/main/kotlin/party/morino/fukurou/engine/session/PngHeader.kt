package party.morino.fukurou.engine.session

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * PNG の署名と IHDR から幅と高さを読む（runner/player_session.py:154-165）。純粋。
 */
internal object PngHeader {
    /** PNG の先頭 8 バイト。 */
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** 署名 8 バイト + IHDR の長さ・型 8 バイト + 幅 4 バイト + 高さ 4 バイト。 */
    private const val HEADER_SIZE = 24

    /** 先頭が PNG の署名か。 */
    fun isPng(header: ByteArray): Boolean =
        header.size >= SIGNATURE.size && SIGNATURE.indices.all { header[it] == SIGNATURE[it] }

    /**
     * 先頭 24 バイトから (幅, 高さ) を読む。
     *
     * @throws IllegalArgumentException PNG でない、または短すぎる
     */
    fun size(header: ByteArray): Pair<Int, Int> {
        require(header.size >= HEADER_SIZE && isPng(header)) { "not a PNG file" }
        // IHDR の幅と高さはビッグエンディアンの 32 ビット（struct.unpack(">II", header[16:24])）
        val buffer = ByteBuffer.wrap(header, 16, 8)
        return buffer.getInt() to buffer.getInt()
    }

    /** ファイルの先頭を読む（短いファイルならその長さだけ）。 */
    fun readHeader(path: Path): ByteArray = Files.newInputStream(path).use { it.readNBytes(HEADER_SIZE) }

    /**
     * ファイルの (幅, 高さ)。
     *
     * @throws IllegalArgumentException PNG でない
     */
    fun size(path: Path): Pair<Int, Int> = try {
        size(readHeader(path))
    } catch (error: IllegalArgumentException) {
        // どのファイルが壊れていたか分かるようパスを付ける
        throw IllegalArgumentException("$path is not a PNG file", error)
    }
}
