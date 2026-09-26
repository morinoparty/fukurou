package party.morino.fukurou.engine.x11

import kotlinx.coroutines.delay
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.InputException
import party.morino.fukurou.player.Chord
import party.morino.fukurou.player.KeySym
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Xvfb 上の Minecraft のウィンドウ（runner/x11_input.py:30-84）。入力の前に必ずフォーカスを確かめる。
 *
 * ウィンドウマネージャーの無い素の Xvfb を前提にしているため、EWMH が必要な windowactivate ではなく
 * XSetInputFocus 相当の windowfocus を使う。
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
    suspend fun focus() {
        if (focusedWindow() == windowId) return
        Xdotool.run(display, "windowraise", windowId.toString())
        Xdotool.run(display, "windowfocus", "--sync", windowId.toString())
        if (focusedWindow() != windowId) {
            throw InputException("could not give keyboard focus to the Minecraft window on $display")
        }
    }

    /** key --clearmodifiers --delay 100 <keycode>。 */
    suspend fun pressKey(key: KeySym) {
        focus()
        // keysym 名のまま渡すと xdotool が F5 を Alt+F5 等に解決することがあるため、修飾キーなしのキーコードで送る
        val keycode = keycodes.unmodifiedKeycode(display, key.keysym)
        Xdotool.run(display, "key", "--clearmodifiers", "--delay", "100", keycode.toString())
    }

    /** キーコードを + でつないで同時に押す。先に書いたキーから順に押し、逆順に離す。 */
    suspend fun pressChord(chord: Chord) {
        focus()
        // pressKey と同じく修飾キーなしのキーコードに変換し、xdotool の "+" 区切りで 1 つのコードとして送る
        val keycodes = chord.keys.map { keycodes.unmodifiedKeycode(display, it.keysym) }
        Xdotool.run(display, "key", "--clearmodifiers", "--delay", "100", keycodes.joinToString("+"))
    }

    /** type --clearmodifiers --delay 50 -- <text>。記号の Shift 等は xdotool が自動で付与する。 */
    suspend fun typeText(text: String) {
        focus()
        Xdotool.run(display, "type", "--clearmodifiers", "--delay", "50", "--", text)
    }

    /** 今キーボードフォーカスを持つウィンドウ。 */
    private suspend fun focusedWindow(): Long {
        val output = Xdotool.run(display, "getwindowfocus", "-f").trim()
        return output.toLongOrNull() ?: throw InputException("xdotool getwindowfocus returned '$output' on $display")
    }

    companion object {
        /** 26.x のクライアントは WM_CLASS が com.mojang.minecraft になる。古い版はタイトルで探す。 */
        private val WINDOW_QUERIES = listOf(
            "--class" to """^com\.mojang\.minecraft$""",
            "--name" to "^Minecraft",
        )

        /** ウィンドウを探し直す間隔。 */
        private val POLL: Duration = 500.milliseconds

        /**
         * --class ^com\.mojang\.minecraft$、次に --name ^Minecraft で探し、最後の id を使う。
         * 0.5 s ごとに探し、timeout で HarnessTimeoutException。キャンセルできる。
         */
        suspend fun waitFor(display: String, keycodes: KeycodeResolver, timeout: Duration): MinecraftWindow {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (!deadline.hasPassedNow()) {
                for ((option, query) in WINDOW_QUERIES) {
                    val ids = Xdotool.run(display, "search", "--onlyvisible", option, query, allowNoMatch = true)
                        .split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                    // 1 つのディスプレイには 1 クライアントしか起動しないので、最後に作られたものを使う
                    if (ids.isNotEmpty()) return MinecraftWindow(display, ids.last(), keycodes)
                }
                // delay なのでレーンの打ち切り（キャンセル）はここで届く
                delay(POLL)
            }
            throw HarnessTimeoutException("Minecraft window did not appear on $display within ${timeout.inWholeSeconds} seconds")
        }
    }
}
