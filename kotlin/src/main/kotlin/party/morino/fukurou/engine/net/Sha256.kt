package party.morino.fukurou.engine.net

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** SHA-256 の 16 進表記（小文字）を求める（net.py:65 sha256_file）。 */
internal object Sha256 {
    /** 大きな jar でもメモリを使いすぎないよう、1 MiB ずつ読む。 */
    private const val CHUNK_SIZE = 1 shl 20

    /** ファイルの SHA-256。 */
    fun of(path: Path): String = Files.newInputStream(path).use { of(it) }

    /** ストリームの残りすべての SHA-256。ストリームは閉じない。 */
    fun of(input: InputStream): String {
        val digest = newDigest()
        val buffer = ByteArray(CHUNK_SIZE)
        // 分割して読みながらハッシュに足す
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /** バイト列の SHA-256。 */
    fun of(bytes: ByteArray): String = hex(newDigest().digest(bytes))

    /** 文字列（UTF-8）の SHA-256。URL からキャッシュのキーを作るのに使う。 */
    fun of(text: String): String = of(text.toByteArray(Charsets.UTF_8))

    /** 新しい SHA-256 の MessageDigest。 */
    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** ダイジェストを小文字の 16 進にする（Python の hexdigest と同じ表記）。 */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
