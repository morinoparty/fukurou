package party.morino.fukurou.engine.net

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 共有キャッシュのロック。JVM 内は path ごとの Mutex、JVM 間は <path>.lock の FileChannel.lock で排他する。
 *
 * 待ちは timeout（既定 15 分）で打ち切り、SetupException にする。
 */
internal object CacheLock {
    /** path を排他して block を実行する。 */
    suspend fun <T> withLock(path: Path, timeout: Duration = 15.minutes, block: suspend () -> T): T =
        TODO("WP2: CacheLock.withLock($path, $timeout, $block)")
}
