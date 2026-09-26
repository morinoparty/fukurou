package party.morino.fukurou.engine.process

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
     */
    suspend fun run(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = 15.seconds,
        cwd: Path? = null,
    ): Result = TODO("WP2: ExternalCommand.run($argv, $env, $timeout, $cwd)")
}
