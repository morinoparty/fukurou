package party.morino.fukurou.spi

import party.morino.fukurou.FukurouConfig
import java.nio.file.Path

/** エンジンが種類の実装に渡すサービス（ダウンロード・キャッシュロック・ハーネスログ）。 */
@FukurouSpi
public interface PlatformServices {
    /** 設定。 */
    public val config: FukurouConfig

    /** 共有キャッシュ（<workDir>/cache）。 */
    public val cacheDir: Path

    /** .part に書いてから sha256 を確かめ、原子的に移す。sha が一致する既存のファイルはそのまま返す。 */
    public suspend fun download(
        url: String,
        destination: Path,
        sha256: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Path

    /** JVM 内とプロセス間の両方で path を排他する（同じバージョンのダウンロードや展開を直列にする）。 */
    public suspend fun <T> withCacheLock(path: Path, block: suspend () -> T): T

    /** 127.0.0.1 の空きポート（FreePort）。ポートの割り当てはエンジンが持ち、種類はここから受け取る。 */
    public fun freePort(): Int

    /** このサーバーの harness.log に書く。 */
    public fun log(message: String)

    /** このサーバーの harness.log に警告として書く。 */
    public fun warn(message: String)
}
