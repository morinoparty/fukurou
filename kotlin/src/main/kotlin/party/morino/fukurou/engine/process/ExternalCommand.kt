package party.morino.fukurou.engine.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.fukurou.error.InputException
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** xdotool・xmodmap・kill・java -XshowSettings のような短い道具を実行する（x11_input.py:86）。 */
internal object ExternalCommand {
    /**
     * 道具の実行結果。
     *
     * @property exit 終了コード
     * @property stdout 標準出力
     * @property stderr 標準エラー
     */
    data class Result(val exit: Int, val stdout: String, val stderr: String)

    /**
     * argv を実行し、終了を待つ。標準出力と標準エラーは IO のコルーチンで読み切る。
     *
     * 時間切れやキャンセルでは destroyForcibly し、時間切れは InputException("<tool> <arg0> timed out after 15s")。
     * 起動できない（道具が無い）場合も InputException。
     */
    suspend fun run(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 15.seconds,
        cwd: Path? = null,
    ): Result {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        val builder = ProcessBuilder(argv).redirectInput(ProcessBuilder.Redirect.from(DEV_NULL))
        // DISPLAY などの追加の環境変数は親の環境に重ねる
        builder.environment().putAll(env)
        cwd?.let { builder.directory(it.toFile()) }
        val process = try {
            runInterruptible(Dispatchers.IO) { builder.start() }
        } catch (error: IOException) {
            throw InputException("could not run ${argv.first()}: ${error.message}")
        }
        try {
            return coroutineScope {
                // パイプのバッファが埋まって道具が止まらないよう、待つ前から両方を読み始める
                val stdout = async(Dispatchers.IO) { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
                val stderr = async(Dispatchers.IO) { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
                val exit = withTimeoutOrNull(timeout) { process.onExit().await().exitValue() }
                if (exit == null) {
                    // 時間切れの道具は強制終了する（パイプが閉じるので読み取りも終わる）
                    process.destroyForcibly()
                    throw InputException("${describe(argv)} timed out after ${timeout.inWholeSeconds}s")
                }
                Result(exit, stdout.await(), stderr.await())
            }
        } finally {
            // キャンセルや例外で抜けたときも道具を残さない
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** メッセージに出す「道具 最初の引数」。 */
    private fun describe(argv: List<String>): String = argv.take(2).joinToString(" ")

    /** 標準入力に渡す空の入力。 */
    private val DEV_NULL = java.io.File("/dev/null")
}
