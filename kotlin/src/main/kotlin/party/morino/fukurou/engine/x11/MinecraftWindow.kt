package party.morino.fukurou.engine.x11

import party.morino.fukurou.player.Chord
import party.morino.fukurou.player.KeySym
import kotlin.time.Duration

/**
 * Xvfb 上の Minecraft のウィンドウ（runner/x11_input.py:30-84）。入力の前に必ずフォーカスを確かめる。
 *
 * @property display DISPLAY の値
 * @property windowId xdotool のウィンドウ id
 * @property keycodes keysym → キーコード
 */
internal class MinecraftWindow(
    val display: String,
    val windowId: Long,
    private val keycodes: KeycodeResolver,
) {
    /** フォーカスが別のウィンドウなら raise + focus --sync し、確かめる。できなければ InputException。 */
    suspend fun focus(): Unit = TODO("WP4: MinecraftWindow.focus($display, $windowId, $keycodes)")

    /** key --clearmodifiers --delay 100 <keycode>。 */
    suspend fun pressKey(key: KeySym): Unit = TODO("WP4: MinecraftWindow.pressKey($key)")

    /** キーコードを + でつないで同時に押す。 */
    suspend fun pressChord(chord: Chord): Unit = TODO("WP4: MinecraftWindow.pressChord($chord)")

    /** type --clearmodifiers --delay 50 -- <text>。 */
    suspend fun typeText(text: String): Unit = TODO("WP4: MinecraftWindow.typeText($text)")

    companion object {
        /**
         * --class ^com\.mojang\.minecraft$、次に --name ^Minecraft で探し、最後の id を使う。
         * 0.5 s ごとに探し、timeout で HarnessTimeoutException。キャンセルできる。
         */
        suspend fun waitFor(display: String, keycodes: KeycodeResolver, timeout: Duration): MinecraftWindow =
            TODO("WP4: MinecraftWindow.waitFor($display, $keycodes, $timeout)")
    }
}
