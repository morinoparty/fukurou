package party.morino.fukurou.engine.client

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * tar.gz（ustar）から通常のファイルを 1 つ取り出す。純粋（ストリームを読むだけ）。
 *
 * PortableMC のアーカイブを展開するためだけの最小の実装で、ディレクトリ・リンク・GNU の長い名前・pax のヘッダーは読み飛ばす。
 */
internal object TarGzReader {
    /** tar のブロックの大きさ。 */
    private const val BLOCK = 512

    /**
     * gzip された tar を input から読み、basename が [name] の最初の通常ファイルの中身を返す。無ければ null。
     * アーカイブ内のディレクトリ構成に依存しないよう、深さは問わない。input は閉じない。
     */
    fun extract(input: InputStream, name: String): ByteArray? = findEntry(GZIPInputStream(input), name)

    /** gzip を解いた tar のストリームから探す。 */
    fun findEntry(tar: InputStream, name: String): ByteArray? {
        while (true) {
            // GZIP のストリームは短く返すことがあるので readNBytes で 1 ブロックを読み切る
            val header = tar.readNBytes(BLOCK)
            // 末尾（全部 0 のブロック）か、途中で切れていれば終わり
            if (header.size < BLOCK || header.all { it.toInt() == 0 }) return null
            val size = parseSize(header)
            val type = header[156].toInt().toChar()
            val path = entryPath(header)
            // '0' と NUL（古い形式）と '7'（連続ファイル）が通常のファイル
            if (type in REGULAR_TYPES && path.substringAfterLast('/') == name) {
                val data = tar.readNBytes(size.toInt())
                if (data.size.toLong() != size) return null
                return data
            }
            // 対象でなければ、512 バイトに切り上げた本体を読み飛ばす
            skipFully(tar, (size + BLOCK - 1) / BLOCK * BLOCK)
        }
    }

    /** 通常のファイルを表す typeflag。 */
    private val REGULAR_TYPES = setOf('0', '\u0000', '7')

    /** ustar の prefix（345..500）と name（0..100）をつないだパス。 */
    private fun entryPath(header: ByteArray): String {
        val name = cString(header, 0, 100)
        // ustar の場合だけ prefix がある（magic は 257 から "ustar"）
        val magic = cString(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** 124..136 の大きさ。通常は 8 進数の文字列、先頭ビットが立っていれば base-256 の数値。 */
    private fun parseSize(header: ByteArray): Long {
        if (header[124].toInt() and 0x80 != 0) {
            // GNU の base-256 形式（8 GiB 以上のファイル）。先頭バイトの印を除いた残りをビッグエンディアンで読む
            var value = (header[124].toLong() and 0x7f)
            for (index in 125 until 136) value = (value shl 8) or (header[index].toLong() and 0xff)
            return value
        }
        val text = cString(header, 124, 12).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    /** NUL で終わる ASCII の文字列を読む。 */
    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it].toInt() == 0 } ?: (offset + length)
        return String(bytes, offset, end - offset, Charsets.ISO_8859_1)
    }

    /** count バイトを読み飛ばす（skip は 0 を返しうるので読んで捨てる）。 */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // skip が進まなければ 1 バイト読んで終端かどうかを確かめる
                if (input.read() < 0) return
                remaining--
            }
        }
    }
}
