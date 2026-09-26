package party.morino.fukurou.engine.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import party.morino.fukurou.error.SetupException
import java.io.InputStream
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream

/**
 * .part に書きながら sha256 を計算し、一致したら原子的に移す（net.py:74-113）。
 *
 * @property userAgent 送る User-Agent（Paper の API は必須）
 */
internal class Downloader(private val userAgent: String = Http.DEFAULT_USER_AGENT) {
    /** ダウンロードに使う HTTP（リダイレクトは通常どおり追う）。 */
    private val http = Http(userAgent)

    /**
     * url を destination にダウンロードする。sha256 が一致する既存のファイルがあればそのまま返す。
     * 不一致なら .part を消して SetupException。destination は CacheLock で排他する。
     */
    suspend fun download(
        url: String,
        destination: Path,
        sha256: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Path = CacheLock.withLock(destination) {
        // 待っている間に他の JVM が同じファイルを揃えていれば、それを使う（cached_download と同じ）
        if (sha256 != null) cached(destination, sha256)?.let { return@withLock it }
        val response = http.send(http.request(url, headers), HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()
        if (status >= 400) {
            // 本文は読まずに閉じ、途中のファイルを残さない
            runInterruptible(Dispatchers.IO) { response.body().close() }
            throw SetupException("download of $url failed with HTTP $status")
        }
        store(response.body(), url, destination, sha256)
    }

    /** destination があり、sha256 が一致する（sha256 が null なら存在する）なら destination、無ければ null。 */
    fun cached(destination: Path, sha256: String?): Path? {
        if (!Files.isRegularFile(destination)) return null
        // sha256 の指定が無ければ存在するだけで良しとする
        if (sha256 == null) return destination
        return destination.takeIf { Sha256.of(it) == sha256.lowercase() }
    }

    /**
     * 本文を destination.part に書きながらハッシュを取り、一致すれば destination へ原子的に移す。
     * body は必ず閉じる。GitHub のアセットのように別のクライアントで受けた本文にも使う。
     */
    suspend fun store(body: InputStream, url: String, destination: Path, sha256: String?): Path =
        runInterruptible(Dispatchers.IO) {
            Files.createDirectories(destination.toAbsolutePath().parent)
            val partial = destination.resolveSibling("${destination.fileName}.part")
            val digest = Sha256.newDigest()
            try {
                // 途中で失敗しても壊れたファイルが残らないよう、一時ファイルに書いてから置き換える
                DigestInputStream(body, digest).use { input ->
                    Files.newOutputStream(partial).use { output -> input.transferTo(output) }
                }
            } catch (error: java.io.IOException) {
                Files.deleteIfExists(partial)
                throw SetupException("download of $url failed: $error", error)
            } catch (error: Throwable) {
                // 割り込み（キャンセル）でも .part を残さない
                Files.deleteIfExists(partial)
                throw error
            }
            val actual = Sha256.hex(digest.digest())
            if (sha256 != null && actual != sha256.lowercase()) {
                Files.deleteIfExists(partial)
                throw SetupException("checksum mismatch for $url: expected sha256 ${sha256.lowercase()}, got $actual")
            }
            // 読み手が中途半端なファイルを見ないよう、同じディレクトリ内で原子的に置き換える
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            destination
        }
}
