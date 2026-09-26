package party.morino.fukurou.engine.x11

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 1 プレイヤー専用の Xvfb（runner/xvfb.py）。
 *
 * @property logFile Xvfb の出力の書き出し先（<player>-xvfb.log）
 */
internal class VirtualDisplay(val logFile: Path) {
    /** ":99" のような DISPLAY の値。start の前に参照すると IllegalStateException。 */
    val display: String get() = TODO("WP4: VirtualDisplay.display")

    /** 起動中か。 */
    val isAlive: Boolean get() = TODO("WP4: VirtualDisplay.isAlive")

    /**
     * 空いている番号で Xvfb を起動し、xdotool getdisplaygeometry が成功するまで待つ（最大 20 番号）。
     * 戻り値は DISPLAY の値。
     */
    suspend fun start(timeout: Duration = 30.seconds): String = TODO("WP4: VirtualDisplay.start($timeout)")

    /** 子プロセスに渡す環境変数（DISPLAY）。 */
    fun environment(): Map<String, String> = mapOf("DISPLAY" to display)

    /** Xvfb を止める。 */
    suspend fun stop(grace: Duration): Unit = TODO("WP4: VirtualDisplay.stop($grace)")
}
