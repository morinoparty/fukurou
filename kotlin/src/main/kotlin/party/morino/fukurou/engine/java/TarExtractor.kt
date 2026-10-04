package party.morino.fukurou.engine.java

import party.morino.fukurou.error.SetupException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * tar.gz を丸ごと展開する（JDK のアーカイブ用）。純粋な JVM の実装で、ホストの tar に頼らない。
 *
 * 通常のファイル（実行ビットを保つ）・ディレクトリ・シンボリックリンク・ハードリンク・GNU の長い名前（L / K）・
 * pax の拡張ヘッダー（x の path / linkpath）を扱う。展開先の外を指すパスやリンクは [SetupException] にする。
 */
internal object TarExtractor {
    /** tar のブロックの大きさ。 */
    private const val BLOCK = 512

    /** 通常のファイルを表す typeflag（'0'、古い形式の NUL、連続ファイルの '7'）。 */
    private val REGULAR_TYPES = setOf('0', '\u0000', '7')

    /**
     * gzip された tar を input から読み、destination の下に展開する。input は閉じない。
     *
     * @param stripComponents 各パスの先頭から外す階層の数（JDK の jdk-21.0.4+7/ を外すなら 1）
     */
    fun extractTarGz(input: InputStream, destination: Path, stripComponents: Int = 0) =
        extractTar(GZIPInputStream(input), destination, stripComponents)

    /** gzip を解いた tar のストリームを destination に展開する。 */
    fun extractTar(tar: InputStream, destination: Path, stripComponents: Int = 0) {
        val root = destination.toAbsolutePath().normalize()
        Files.createDirectories(root)
        // 書き込み先の判定は実際のパス（リンクを解決したもの）で行う。字面だけだと既に作ったリンクを通って外に出られる
        val realRoot = root.toRealPath()
        // GNU の長い名前と pax のヘッダーは、次の 1 エントリにだけ効く
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            // GZIP のストリームは短く返すことがあるので readNBytes で 1 ブロックを読み切る
            val header = tar.readNBytes(BLOCK)
            if (header.size < BLOCK || header.all { it.toInt() == 0 }) return
            val size = parseNumber(header, 124, 12)
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> {
                    longName = cString(readData(tar, size))
                    continue
                }
                'K' -> {
                    longLink = cString(readData(tar, size))
                    continue
                }
                'x' -> {
                    val records = parsePax(readData(tar, size))
                    paxPath = records["path"] ?: paxPath
                    paxLink = records["linkpath"] ?: paxLink
                    continue
                }
            }
            val name = paxPath ?: longName ?: headerPath(header)
            val linkName = paxLink ?: longLink ?: cString(header, 157, 100)
            longName = null
            longLink = null
            paxPath = null
            paxLink = null
            val target = resolve(root, name, stripComponents)
            when {
                // 外した結果が空（最上位のディレクトリそのもの）なら何も作らない
                target == null -> skip(tar, size)
                type == '5' -> {
                    realDirectory(realRoot, target, name)
                    skip(tar, size)
                }
                type in REGULAR_TYPES -> {
                    val file = realDirectory(realRoot, target.parent, name).resolve(target.fileName)
                    Files.deleteIfExists(file)
                    Files.newOutputStream(file).use { output -> copyExactly(tar, output, size, name) }
                    skipPadding(tar, size)
                    Files.setPosixFilePermissions(file, permissions(parseNumber(header, 100, 8).toInt()))
                }
                type == '2' -> {
                    symlink(realRoot, target, linkName, name)
                    skip(tar, size)
                }
                type == '1' -> {
                    // ハードリンクはアーカイブ内の別のパスを指す。同じ外し方で解決して中身を写す
                    val source = resolve(root, linkName, stripComponents)
                        ?: throw SetupException("hard link $name points outside the archive root: $linkName")
                    if (!Files.isRegularFile(source)) throw SetupException("hard link $name points to a missing file $linkName")
                    val realSource = source.toRealPath()
                    if (!realSource.startsWith(realRoot)) throw SetupException("hard link $name points outside the archive: $linkName")
                    val file = realDirectory(realRoot, target.parent, name).resolve(target.fileName)
                    Files.copy(realSource, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    skip(tar, size)
                }
                // 'g'（pax の全体のヘッダー）や FIFO などは使わないので読み飛ばす
                else -> skip(tar, size)
            }
        }
    }

    /** アーカイブ内のパスを展開先のパスにする。外した結果が空なら null、外に出るなら SetupException。 */
    private fun resolve(root: Path, name: String, stripComponents: Int): Path? {
        if (name.startsWith("/")) throw SetupException("archive entry has an absolute path: $name")
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in parts) throw SetupException("archive entry escapes the destination: $name")
        val kept = parts.drop(stripComponents)
        if (kept.isEmpty()) return null
        val path = root.resolve(kept.joinToString("/")).normalize()
        if (!path.startsWith(root)) throw SetupException("archive entry escapes the destination: $name")
        return path
    }

    /**
     * directory を作り、リンクを解決した実際のパスを返す。展開先の外に出るなら SetupException。
     *
     * 作る前に、既にある最も近い祖先の実際のパスを確かめる（先に作ったリンクを通って外にディレクトリを作らない）。
     */
    private fun realDirectory(realRoot: Path, directory: Path, name: String): Path {
        var existing: Path = directory
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.parent ?: throw SetupException("archive entry escapes the destination: $name")
        }
        ensureInside(realRoot, realPath(existing, name), name)
        Files.createDirectories(directory)
        return realPath(directory, name).also { ensureInside(realRoot, it, name) }
    }

    /** リンクを解決した実際のパス。たどれないリンク（先が無い）なら SetupException。 */
    private fun realPath(path: Path, name: String): Path =
        try {
            path.toRealPath()
        } catch (error: java.io.IOException) {
            throw SetupException("archive entry $name goes through a broken symbolic link: $error", error)
        }

    /** 実際のパスが展開先の中であることを確かめる。 */
    private fun ensureInside(realRoot: Path, real: Path, name: String) {
        if (!real.startsWith(realRoot)) throw SetupException("archive entry escapes the destination through a symbolic link: $name")
    }

    /**
     * シンボリックリンクを作る。リンク先が展開先の外になるものは作らない。
     *
     * リンク先の ".." は先頭にだけ許す（途中のリンクを通ってから上がると、字面と実際の行き先が食い違う）。
     * 先頭の ".." はリンクを置くディレクトリの実際のパスから数える。
     */
    private fun symlink(realRoot: Path, target: Path, linkName: String, name: String) {
        if (linkName.isEmpty() || linkName.startsWith("/")) throw SetupException("symbolic link $name points outside the archive: $linkName")
        val parts = linkName.split('/').filter { it.isNotEmpty() && it != "." }
        val firstNormal = parts.indexOfFirst { it != ".." }
        if (firstNormal >= 0 && ".." in parts.drop(firstNormal)) {
            throw SetupException("symbolic link $name has '..' after a directory name: $linkName")
        }
        val parent = realDirectory(realRoot, target.parent, name)
        val pointed = parent.resolve(linkName).normalize()
        if (!pointed.startsWith(realRoot)) throw SetupException("symbolic link $name points outside the archive: $linkName")
        val link = parent.resolve(target.fileName)
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, Path.of(linkName))
    }

    /** ustar の prefix（345..500）と name（0..100）をつないだパス。 */
    private fun headerPath(header: ByteArray): String {
        val name = cString(header, 0, 100)
        // ustar の場合だけ prefix がある（magic は 257 から "ustar"）
        val magic = cString(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** 8 進数の文字列か、先頭ビットが立っていれば base-256 の数値（GNU の大きなファイル）。 */
    private fun parseNumber(header: ByteArray, offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var value = header[offset].toLong() and 0x7f
            for (index in offset + 1 until offset + length) value = (value shl 8) or (header[index].toLong() and 0xff)
            return value
        }
        val text = cString(header, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    /** tar の mode（下位 9 ビット）を POSIX の権限にする。 */
    private fun permissions(mode: Int): Set<PosixFilePermission> {
        // PosixFilePermission の並びは OWNER_READ から OTHERS_EXECUTE までで、mode の上位ビットからの順と同じ
        val all = PosixFilePermission.entries
        return all.filterIndexed { index, _ -> mode and (1 shl (8 - index)) != 0 }.toSet()
    }

    /** pax の拡張ヘッダー（"<長さ> <キー>=<値>\n" の並び）を読む。 */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val records = mutableMapOf<String, String>()
        var position = 0
        while (position < data.size) {
            val space = (position until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val length = String(data, position, space - position, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || position + length > data.size) break
            // 長さは自身と末尾の改行を含む
            val record = String(data, space + 1, position + length - space - 2, Charsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) records[record.substring(0, equals)] = record.substring(equals + 1)
            position += length
        }
        return records
    }

    /** size バイトの本体を読み、512 バイトの境目まで読み飛ばす。 */
    private fun readData(tar: InputStream, size: Long): ByteArray {
        val data = tar.readNBytes(size.toInt())
        if (data.size.toLong() != size) throw SetupException("truncated tar archive")
        skipPadding(tar, size)
        return data
    }

    /** 本体を output へちょうど size バイト写す。 */
    private fun copyExactly(tar: InputStream, output: java.io.OutputStream, size: Long, name: String) {
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw SetupException("truncated tar archive at $name")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** 本体（512 バイトに切り上げ）を読み飛ばす。 */
    private fun skip(tar: InputStream, size: Long) = skipFully(tar, (size + BLOCK - 1) / BLOCK * BLOCK)

    /** 本体の後ろの埋め草を読み飛ばす。 */
    private fun skipPadding(tar: InputStream, size: Long) = skipFully(tar, (BLOCK - size % BLOCK) % BLOCK)

    /** count バイトを読み飛ばす（skip は 0 を返しうるので読んで捨てる）。 */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw SetupException("truncated tar archive")
                remaining--
            }
        }
    }

    /** NUL で終わる文字列（GNU の長い名前の本体）。 */
    private fun cString(bytes: ByteArray): String = cString(bytes, 0, bytes.size)

    /** NUL で終わる文字列を読む。 */
    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it].toInt() == 0 } ?: (offset + length)
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }
}
