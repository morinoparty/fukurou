package party.morino.fukurou.engine.services

import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.net.CacheLock
import party.morino.fukurou.engine.net.Downloader
import party.morino.fukurou.engine.process.FreePort
import party.morino.fukurou.spi.PlatformServices
import java.nio.file.Path

/**
 * エンジンが種類の実装に渡す PlatformServices。1 サーバーにつき 1 つ。
 *
 * @property config 設定
 * @property downloader 共有のダウンローダー
 * @property logSink このサーバーの harness.log への書き込み
 * @property warnSink 同じく警告
 */
internal class EnginePlatformServices(
    override val config: FukurouConfig,
    private val downloader: Downloader,
    private val logSink: (String) -> Unit,
    private val warnSink: (String) -> Unit,
) : PlatformServices {
    override val cacheDir: Path get() = config.workDir.resolve("cache")

    override suspend fun download(url: String, destination: Path, sha256: String?, headers: Map<String, String>): Path =
        downloader.download(url, destination, sha256, headers)

    override suspend fun <T> withCacheLock(path: Path, block: suspend () -> T): T = CacheLock.withLock(path, block = block)

    override fun freePort(): Int = FreePort.next()

    override fun log(message: String) = logSink(message)

    override fun warn(message: String) = warnSink(message)
}
