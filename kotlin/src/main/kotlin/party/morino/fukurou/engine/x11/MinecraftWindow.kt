package party.morino.fukurou.engine.x11

import kotlinx.coroutines.delay
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.InputException
import party.morino.fukurou.player.Chord
import party.morino.fukurou.player.KeySym
import party.morino.fukurou.player.MouseButton
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Xvfb 上の Minecraft のウィンドウ。入力の前に必ずフォーカスを確かめる。
 *
 * ウィンドウマネージャーの無い素の Xvfb を前提にしているため、EWMH が必要な windowactivate ではなく
 * XSetInputFocus 相当の windowfocus を使う。
 *
 * 押したままの入力（keydown / keyup / mousedown / mouseup / click）には --clearmodifiers を付けない。
 * xdotool は --clearmodifiers のとき今押されている修飾キーを離してから送り、終わったら押し直すため、
 * keyDown(SHIFT) の後の keyUp(SHIFT) が Shift を押し直してしまい、Shift を押したままのクリック（素早い移動）もできなくなる。
 * Xvfb には物理キーボードが無く、押されている修飾キーはハーネスが押したものだけなので、外す必要も無い。
 * 1 回だけ押す pressKey / pressChord / typeText は従来どおり --clearmodifiers を付けるので、
 * SHIFT を押したまま呼ぶとその間（約 100 ms）だけ Shift が離れる（スニークが一瞬解ける）。
 *
 * key・ボタンの送信に --window を付けると xdotool は XSendEvent で送り、GLFW は合成のイベントを無視することがあるため、
 * フォーカスを確かめてから --window 無しで送る。--window を付けるのはウィンドウ基準の座標になる mousemove だけ。
 *
 * @property display DISPLAY の値
 * @property windowId xdotool のウィンドウ id
 * @property keycodes keysym → キーコード
 */
internal class MinecraftWindow(
    val display: String,
    val windowId: Long,
    private val keycodes: KeycodeResolver,
) : HeldInputDevice {
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
        Xdotool.run(display, "key", "--clearmodifiers", "--delay", "100", keyToken(key.keysym, keycode))
    }

    /** キーコードを + でつないで同時に押す。先に書いたキーから順に押し、逆順に離す。 */
    suspend fun pressChord(chord: Chord) {
        focus()
        // pressKey と同じく修飾キーなしのキーコードに変換し、xdotool の "+" 区切りで 1 つのコードとして送る
        val codes = chord.keys.map { keyToken(it.keysym, keycodes.unmodifiedKeycode(display, it.keysym)) }
        Xdotool.run(display, "key", "--clearmodifiers", "--delay", "100", codes.joinToString("+"))
    }

    /** type --clearmodifiers --delay 50 -- <text>。記号の Shift 等は xdotool が自動で付与する。 */
    suspend fun typeText(text: String) {
        focus()
        Xdotool.run(display, "type", "--clearmodifiers", "--delay", "50", "--", text)
    }

    /** キーを押したままにする（keydown <keycode>）。 */
    suspend fun keyDown(key: KeySym) {
        focus()
        Xdotool.run(display, *keyDownArgs(token(key)))
    }

    /** 押したままのキーを離す（keyup <keycode>）。 */
    override suspend fun keyUp(key: KeySym) {
        focus()
        Xdotool.run(display, *keyUpArgs(token(key)))
    }

    /** マウスをウィンドウの座標 (x, y) へ動かす（mousemove --window <id> x y）。 */
    suspend fun mouseMove(x: Int, y: Int) {
        focus()
        Xdotool.run(display, *mouseMoveArgs(windowId, x, y))
    }

    /** 今のカーソルの位置でクリックする（click <button>）。 */
    suspend fun click(button: MouseButton) {
        focus()
        Xdotool.run(display, *clickArgs(button))
    }

    /** ボタンを押したままにする（mousedown <button>）。 */
    suspend fun mouseDown(button: MouseButton) {
        focus()
        Xdotool.run(display, *mouseDownArgs(button))
    }

    /** 押したままのボタンを離す（mouseup <button>）。 */
    override suspend fun mouseUp(button: MouseButton) {
        focus()
        Xdotool.run(display, *mouseUpArgs(button))
    }

    /**
     * ホイールを steps 段回す。正で下（ボタン 5）、負で上（ボタン 4）。
     * 1 回の xdotool は 15 秒で打ち切られるので、SCROLL_CHUNK 段ずつに分けて送る。
     */
    suspend fun scroll(steps: Int) {
        focus()
        for (chunk in scrollChunks(steps)) Xdotool.run(display, *scrollArgs(chunk))
    }

    /** pressKey と同じく、修飾キーなしで押せるキーコードのトークンにする。 */
    private suspend fun token(key: KeySym): String = keyToken(key.keysym, keycodes.unmodifiedKeycode(display, key.keysym))

    /** 今キーボードフォーカスを持つウィンドウ。 */
    private suspend fun focusedWindow(): Long {
        val output = Xdotool.run(display, "getwindowfocus", "-f").trim()
        return output.toLongOrNull() ?: throw InputException("xdotool getwindowfocus returned '$output' on $display")
    }

    companion object {
        /**
         * 純粋関数。xdotool key に渡す 1 キー分のトークン。
         *
         * xdotool はまずトークンを XStringToKeysym で keysym 名として読み、読めなかったときだけ数字をキーコードとみなす。
         * 1 桁の数字（"0"〜"9"）は数字キーの keysym 名として読まれてしまうため（Escape のキーコード 9 が '9' になる）、
         * 10 未満のキーコードは keysym 名のまま渡す。修飾キーなしで押せることは呼び出し側が KeycodeResolver で確かめ済み。
         */
        fun keyToken(keysym: String, keycode: Int): String =
            if (keycode < SINGLE_DIGIT_LIMIT) keysym else keycode.toString()

        /** ウィンドウの幅（ClientOptions.RESOLUTION の 1280x720）。 */
        const val WIDTH: Int = 1280

        /** ウィンドウの高さ。 */
        const val HEIGHT: Int = 720

        /** ホイールの上のボタン番号。 */
        private const val WHEEL_UP = 4

        /** ホイールの下のボタン番号。 */
        private const val WHEEL_DOWN = 5

        /** ホイールを続けて回すときの 1 段ごとの間隔（ミリ秒）。クライアントが 1 段ずつ数えられるようにする。 */
        private const val SCROLL_DELAY_MS = 50

        /** 純粋関数。座標がウィンドウ（0〜1279, 0〜719）の中か確かめる。外なら IllegalArgumentException。 */
        fun checkPoint(x: Int, y: Int) {
            require(x in 0 until WIDTH && y in 0 until HEIGHT) {
                "($x, $y) is outside the ${WIDTH}x$HEIGHT Minecraft window (x must be 0..${WIDTH - 1}, y must be 0..${HEIGHT - 1})"
            }
        }

        /** 純粋関数。keydown の引数（--clearmodifiers は付けない。クラスの説明を参照）。 */
        fun keyDownArgs(token: String): Array<String> = arrayOf("keydown", token)

        /** 純粋関数。keyup の引数。 */
        fun keyUpArgs(token: String): Array<String> = arrayOf("keyup", token)

        /** 純粋関数。ウィンドウ基準の mousemove の引数。座標は checkPoint で確かめる。 */
        fun mouseMoveArgs(windowId: Long, x: Int, y: Int): Array<String> {
            checkPoint(x, y)
            // --sync は既にその位置にあると動きを待ち続けるので付けない
            return arrayOf("mousemove", "--window", windowId.toString(), x.toString(), y.toString())
        }

        /** 純粋関数。click の引数。 */
        fun clickArgs(button: MouseButton): Array<String> = arrayOf("click", button.xButton.toString())

        /** 純粋関数。mousedown の引数。 */
        fun mouseDownArgs(button: MouseButton): Array<String> = arrayOf("mousedown", button.xButton.toString())

        /** 純粋関数。mouseup の引数。 */
        fun mouseUpArgs(button: MouseButton): Array<String> = arrayOf("mouseup", button.xButton.toString())

        /** 1 回の xdotool で回すホイールの段数の上限（100 段 × 50 ms = 5 秒。Xdotool の 15 秒の打ち切りより十分短い）。 */
        const val SCROLL_CHUNK: Int = 100

        /**
         * 純粋関数。steps を、符号を保ったまま SCROLL_CHUNK 段以下の塊に分ける（250 → 100, 100, 50）。steps は 0 以外。
         * 大きな steps でも塊を一度に作らないよう、遅延評価の Sequence で返す。
         */
        fun scrollChunks(steps: Int): Sequence<Int> {
            require(steps != 0) { "scroll steps must not be 0" }
            val sign = if (steps > 0) 1 else -1
            // Int.MIN_VALUE の絶対値は表せないので Long で数える
            val total = abs(steps.toLong())
            return generateSequence(0L) { it + SCROLL_CHUNK }
                .takeWhile { it < total }
                .map { done -> sign * minOf(total - done, SCROLL_CHUNK.toLong()).toInt() }
        }

        /** 純粋関数。ホイールの引数（click --repeat <n> --delay 50 <4|5>）。steps は 0 以外。 */
        fun scrollArgs(steps: Int): Array<String> {
            require(steps != 0) { "scroll steps must not be 0" }
            val button = if (steps > 0) WHEEL_DOWN else WHEEL_UP
            // Int.MIN_VALUE の絶対値は表せないので Long で数える
            val count = abs(steps.toLong())
            return arrayOf("click", "--repeat", count.toString(), "--delay", SCROLL_DELAY_MS.toString(), button.toString())
        }

        /** これ未満のキーコードは 1 桁の数字になり、xdotool が keysym 名と取り違える。 */
        private const val SINGLE_DIGIT_LIMIT = 10

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
