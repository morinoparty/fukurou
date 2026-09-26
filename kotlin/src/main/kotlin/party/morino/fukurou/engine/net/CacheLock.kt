package party.morino.fukurou.engine.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.fukurou.error.SetupException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * 共有キャッシュのロック。JVM 内は path ごとの Mutex、JVM 間は <path>.lock の FileChannel.lock で排他する。
 *
 * 待ちは timeout（既定 15 分）で打ち切り、SetupException にする。時間を測るのは取得までで、block の実行時間は含めない。
 * 同じコルーチンの中で同じ path を入れ子に取ると、そのまま block を実行する（再入できる）。
 */
internal object CacheLock {
    /** JVM 内の path ごとの Mutex。キーは絶対パスに正規化する。 */
    private val mutexes = ConcurrentHashMap<Path, Mutex>()

    /** ファイルロックを取り直す間隔。 */
    private val POLL = 100.milliseconds

    /** path を排他して block を実行する。 */
    suspend fun <T> withLock(path: Path, timeout: Duration = 15.minutes, block: suspend () -> T): T {
        val key = path.toAbsolutePath().normalize()
        // 同じコルーチンで既に持っているなら取り直さない（Mutex は再入できず、FileLock は同じ JVM で重ねると例外になる）
        val held = coroutineContext[HeldLocks]
        if (held != null && key in held.paths) return block()
        val started = TimeSource.Monotonic.markNow()
        val mutex = mutexes.computeIfAbsent(key) { Mutex() }
        // JVM 内の待ち。時間切れなら取れていないので解放しない
        withTimeoutOrNull(timeout) { mutex.lock() } ?: throw timedOut(key, timeout)
        try {
            // 残りの時間で JVM 間のロックを取る
            val fileLock = acquireFileLock(key, timeout - started.elapsedNow()) ?: throw timedOut(key, timeout)
            try {
                // 入れ子の呼び出しから見えるよう、持っているパスをコンテキストに積む
                // Path は Iterable<Path> なので、+ key だと名前の要素ごとに足されてしまう。setOf で包む
                val next = HeldLocks((held?.paths ?: emptySet()) union setOf(key))
                return withContext(next) { block() }
            } finally {
                // チャネルを閉じるとロックも外れる。.lock ファイルは他の JVM が開いているかもしれないので消さない
                runCatching { fileLock.release() }
                runCatching { fileLock.channel().close() }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** <path>.lock を tryLock で取れるまで待つ。remaining を過ぎたら null。スレッドを塞がないよう delay で待つ。 */
    private suspend fun acquireFileLock(key: Path, remaining: Duration): FileLock? {
        val lockFile = key.resolveSibling("${key.fileName}.lock")
        val channel = runInterruptible(Dispatchers.IO) {
            // ロックファイルの置き場所が無ければ作る（キャッシュのディレクトリは初回には無い）
            Files.createDirectories(lockFile.parent)
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        }
        try {
            val deadline = TimeSource.Monotonic.markNow() + remaining
            while (true) {
                // 他の JVM が持っていれば null が返るので、間を置いて取り直す
                val lock = runInterruptible(Dispatchers.IO) { channel.tryLock() }
                if (lock != null) return lock
                if (deadline.hasPassedNow()) {
                    channel.close()
                    return null
                }
                delay(POLL)
            }
        } catch (error: Throwable) {
            // キャンセルや I/O の失敗ではチャネルを閉じてから伝える
            channel.close()
            throw error
        }
    }

    /** 待ちの時間切れの例外。 */
    private fun timedOut(key: Path, timeout: Duration): SetupException =
        SetupException("timed out after $timeout waiting for the cache lock $key.lock (another fukurou run may be stuck)")

    /**
     * このコルーチンが持っているロックのパス。入れ子の withLock を検出するために使う。
     *
     * @property paths 持っているパス（絶対パス）
     */
    private class HeldLocks(val paths: Set<Path>) : AbstractCoroutineContextElement(HeldLocks) {
        /** コンテキストのキー。 */
        companion object : CoroutineContext.Key<HeldLocks>
    }
}
