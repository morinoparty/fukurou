package party.morino.fukurou.engine.net

import java.nio.file.Path

/**
 * .part に書きながら sha256 を計算し、一致したら原子的に移す（net.py:74-113）。
 *
 * @property userAgent 送る User-Agent（Paper の API は必須）
 */
internal class Downloader(private val userAgent: String) {
    /**
     * url を destination にダウンロードする。sha256 が一致する既存のファイルがあればそのまま返す。
     * 不一致なら .part を消して SetupException。destination は CacheLock で排他する。
     */
    suspend fun download(
        url: String,
        destination: Path,
        sha256: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Path = TODO("WP2: Downloader.download($userAgent, $url, $destination, $sha256, $headers)")

    /** destination があり、sha256 が一致する（sha256 が null なら存在する）なら destination、無ければ null。 */
    fun cached(destination: Path, sha256: String?): Path? = TODO("WP2: Downloader.cached($destination, $sha256)")
}
