package party.morino.fukurou.engine.x11

/**
 * keysym を修飾キーなしで押せるキーコードへ解決する（runner/x11_input.py:108）。キーマップは display ごとにキャッシュする。
 *
 * @property loadKeymap display の `xmodmap -display <d> -pke` の出力を返す（テストでは固定の文字列を渡す）
 */
internal class KeycodeResolver(private val loadKeymap: suspend (display: String) -> String) {
    /**
     * 1 列目（group 0 level 0）が keysym の最小のキーコード。
     * 後ろの列にしか無ければ InputException("<k> cannot be typed without modifiers; use typeText")、
     * 無ければ InputException("unknown X11 keysym: <k>")。
     */
    suspend fun unmodifiedKeycode(display: String, keysym: String): Int =
        TODO("WP4: KeycodeResolver.unmodifiedKeycode($loadKeymap, $display, $keysym)")

    /** display を起動し直したときにキャッシュを捨てる。 */
    fun invalidate(display: String): Unit = TODO("WP4: KeycodeResolver.invalidate($display)")
}
