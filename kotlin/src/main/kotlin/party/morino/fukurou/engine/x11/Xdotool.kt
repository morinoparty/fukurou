package party.morino.fukurou.engine.x11

import kotlinx.coroutines.CancellationException
import party.morino.fukurou.engine.process.ExternalCommand
import party.morino.fukurou.error.InputException
import kotlin.time.Duration.Companion.seconds

/**
 * 指定ディスプレイに対して xdotool を実行する（runner/x11_input.py:86-106）。
 *
 * プレイヤーごとに別のディスプレイを使うため、DISPLAY は呼び出しごとに子プロセスの環境へ渡す。
 */
internal object Xdotool {
    /** 1 回の呼び出しの時間切れ。 */
    private val TIMEOUT = 15.seconds

    /**
     * xdotool arguments を display で実行し、標準出力を返す。
     * search は一致なしの場合に終了コード 1 を返すので、allowNoMatch なら出力が空の失敗を正常扱いにする。
     */
    suspend fun run(display: String, vararg arguments: String, allowNoMatch: Boolean = false): String {
        val result = ExternalCommand.run(listOf("xdotool", *arguments), mapOf("DISPLAY" to display), TIMEOUT)
        if (result.exit != 0 && !(allowNoMatch && result.stdout.isBlank())) {
            throw InputException("xdotool ${arguments.joinToString(" ")} failed: ${result.stderr.trim()}")
        }
        return result.stdout
    }

    /** xdotool が display に接続できるか（Xvfb の起動待ちに使う、実際の X のハンドシェイク）。 */
    suspend fun canConnect(display: String): Boolean = runCatching {
        ExternalCommand.run(listOf("xdotool", "getdisplaygeometry"), mapOf("DISPLAY" to display), TIMEOUT).exit == 0
    }.getOrElse { error ->
        // キャンセルは伝え、道具の時間切れなどは「まだ接続できない」とみなす
        if (error is CancellationException) throw error
        false
    }
}
