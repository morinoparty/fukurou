package party.morino.fukurou.engine.x11

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.morino.fukurou.engine.process.ExternalCommand
import party.morino.fukurou.error.InputException
import kotlin.time.Duration.Companion.seconds

/**
 * keysym を修飾キーなしで押せるキーコードへ解決する（runner/x11_input.py:108）。キーマップは display ごとにキャッシュする。
 *
 * keysym 名のまま xdotool に渡すと F5 を Alt+F5 などに解決することがあるため、
 * 修飾キーなしで届く物理キーコード（10 進数）に変換して送る。
 *
 * @property loadKeymap display の `xmodmap -display <d> -pke` の出力を返す（テストでは固定の文字列を渡す）
 */
internal class KeycodeResolver(private val loadKeymap: suspend (display: String) -> String) {
    /** display ごとのキーマップ。 */
    private val keymaps = HashMap<String, Map<Int, List<String>>>()

    /** キャッシュの読み書きと読み込みを直列にする（並行するレーンが同じ display を同時に読まないように）。 */
    private val lock = Mutex()

    /**
     * 1 列目（group 0 level 0）が keysym の最小のキーコード。
     * 後ろの列にしか無ければ InputException("<k> cannot be typed without modifiers; use typeText")、
     * 無ければ InputException("unknown X11 keysym: <k>")。
     */
    suspend fun unmodifiedKeycode(display: String, keysym: String): Int {
        val keymap = keymap(display)
        // シフト無しの面（group 0, level 0）で同じ keysym になるキーだけを受け付ける。表は昇順なので最初が最小
        keymap.entries.firstOrNull { (_, columns) -> columns.firstOrNull()?.let { KeymapParser.matches(it, keysym) } == true }
            ?.let { return it.key }
        // 大文字の A のように Shift が要るものは typeText で入力してもらう
        if (keymap.values.any { columns -> columns.any { KeymapParser.matches(it, keysym) } }) {
            throw InputException("$keysym cannot be typed without modifiers; use typeText")
        }
        throw InputException("unknown X11 keysym: $keysym")
    }

    /** display を起動し直したときにキャッシュを捨てる。 */
    fun invalidate(display: String) {
        // suspend でない呼び出し元（Xvfb の停止）からも消せるよう、Mutex ではなく表そのものを同期する
        synchronized(keymaps) { keymaps.remove(display) }
    }

    /** キャッシュを使ってキーマップを返す。 */
    private suspend fun keymap(display: String): Map<Int, List<String>> = lock.withLock {
        synchronized(keymaps) { keymaps[display] }?.let { return@withLock it }
        val parsed = KeymapParser.parse(loadKeymap(display))
        synchronized(keymaps) { keymaps[display] = parsed }
        parsed
    }

    companion object {
        /** xmodmap の呼び出しの時間切れ（xdotool と同じ 15 秒）。 */
        private val TIMEOUT = 15.seconds

        /** `xmodmap -display <d> -pke` でキーマップを読むリゾルバー。 */
        fun xmodmap(): KeycodeResolver = KeycodeResolver { display ->
            val result = ExternalCommand.run(listOf("xmodmap", "-display", display, "-pke"), timeout = TIMEOUT)
            if (result.exit != 0) {
                throw InputException("could not read the keymap of $display (xmodmap exited with ${result.exit}): ${result.stderr.trim()}")
            }
            result.stdout
        }
    }
}
