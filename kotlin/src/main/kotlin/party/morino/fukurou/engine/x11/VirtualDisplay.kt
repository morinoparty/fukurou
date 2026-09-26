package party.morino.fukurou.engine.x11

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import party.morino.fukurou.engine.process.ManagedProcess
import party.morino.fukurou.engine.process.ProcessLauncher
import party.morino.fukurou.engine.process.ProcessRegistry
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 1 プレイヤー専用の Xvfb（runner/xvfb.py）。
 *
 * 1 つのディスプレイに複数のクライアントを置くとキーボードフォーカスを奪い合うため、
 * クライアント 1 つにつきディスプレイを 1 つ用意する。
 *
 * @property logFile Xvfb の出力の書き出し先（<player>-xvfb.log）
 * @property allocator ディスプレイ番号の割り当て（JVM で共有）
 */
internal class VirtualDisplay(
    val logFile: Path,
    private val allocator: DisplayAllocator = DisplayAllocator.SHARED,
) {
    /** 起動中の Xvfb。 */
    @Volatile
    private var process: ManagedProcess? = null

    /** 使っているディスプレイ番号。 */
    @Volatile
    private var number: Int? = null

    /** ":99" のような DISPLAY の値。start の前に参照すると IllegalStateException。 */
    val display: String get() = ":" + (number ?: throw IllegalStateException("the virtual display has not been started"))

    /** 起動中か。 */
    val isAlive: Boolean get() = process?.isAlive == true

    /**
     * 空いている番号で Xvfb を起動し、xdotool getdisplaygeometry が成功するまで待つ（最大 20 番号）。
     * 戻り値は DISPLAY の値。
     */
    suspend fun start(timeout: Duration = 30.seconds): String {
        var start = DisplayAllocator.FIRST_DISPLAY_NUMBER
        repeat(MAX_ATTEMPTS) {
            val candidate = allocator.allocate(start)
            if (tryStart(candidate, timeout)) {
                number = candidate
                return ":$candidate"
            }
            // 同時に別の Xvfb が同じ番号を取った等で起動できなかった場合は、次の番号で再試行する
            allocator.release(candidate)
            start = candidate + 1
        }
        throw SetupException("could not start Xvfb after $MAX_ATTEMPTS attempts; see $logFile")
    }

    /** 子プロセスに渡す環境変数（DISPLAY）。 */
    fun environment(): Map<String, String> = mapOf("DISPLAY" to display)

    /** Xvfb を止める。 */
    suspend fun stop(grace: Duration) {
        val current = process ?: return
        current.stop(grace)
        ProcessRegistry.unregister(current)
        process = null
        // 止めた番号は他のプレイヤーが使えるように返す（ロックファイルが残っていれば割り当てで飛ばされる）
        number?.let(allocator::release)
        number = null
    }

    /** candidate で Xvfb を起動し、接続できるまで待つ。Xvfb が先に終了したら false。 */
    private suspend fun tryStart(candidate: Int, timeout: Duration): Boolean {
        val display = ":$candidate"
        // -noreset: 最後のクライアントが切断してもサーバーをリセットしない（GLFW が初期化時に一度切断するため）
        val argv = listOf("Xvfb", display, "-screen", "0", SCREEN, "-nolisten", "tcp", "-noreset")
        val launched = runInterruptible(Dispatchers.IO) {
            // 番号の衝突で終了した試行や、再起動前の出力をエラーの手がかりとして残すため追記する（xvfb.py の "ab"）
            Files.createDirectories(logFile.toAbsolutePath().parent)
            Files.writeString(
                logFile,
                "--- Xvfb $display attempt ---\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            ProcessLauncher.launch("Xvfb $display", argv, logFile.toAbsolutePath().parent, logFile, append = true)
        }
        ProcessRegistry.register(ProcessRegistry.Kind.DISPLAY, launched)
        process = launched
        val deadline = TimeSource.Monotonic.markNow() + timeout
        try {
            while (!deadline.hasPassedNow()) {
                // 番号の衝突などで Xvfb が終了していれば、次の番号を試す
                if (!launched.isAlive) {
                    ProcessRegistry.unregister(launched)
                    process = null
                    return false
                }
                // ctypes の XOpenDisplay の代わりに、xdotool で実際に X のハンドシェイクをする
                if (Xdotool.canConnect(display)) return true
                delay(POLL)
            }
        } catch (error: Throwable) {
            // キャンセルでも Xvfb を残さない
            stopLaunched(launched)
            throw error
        }
        stopLaunched(launched)
        throw SetupException("Xvfb on $display did not accept connections within ${timeout.inWholeSeconds} seconds; see $logFile")
    }

    /** 起動待ちの途中で Xvfb を止める。 */
    private suspend fun stopLaunched(launched: ManagedProcess) {
        withContext(NonCancellable) { runCatching { launched.stop(STOP_GRACE) } }
        ProcessRegistry.unregister(launched)
        process = null
    }

    private companion object {
        /** 画面の大きさと色深度。 */
        const val SCREEN: String = "1280x720x24"

        /** 試す番号の数。 */
        const val MAX_ATTEMPTS: Int = 20

        /** 接続を確かめる間隔。 */
        val POLL: Duration = 200.milliseconds

        /** 起動に失敗した Xvfb を止める猶予（xvfb.py の stop と同じ）。 */
        val STOP_GRACE: Duration = 5.seconds
    }
}
