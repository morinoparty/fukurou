package party.morino.fukurou.player

/**
 * X11 の keysym 名（F5、t、Return など）。Adventure の Key と名前が衝突しないよう KeySym とする。
 *
 * @property keysym keysym の名前
 */
@JvmInline
public value class KeySym(public val keysym: String) {
    init {
        // xdotool に渡すので、keysym 名に使われない文字（空白や +）は受け付けない
        require(NAME.matches(keysym)) { "'$keysym' is not an X11 keysym name" }
    }

    /** 同時に押すキーの組（F3 + D）。 */
    public operator fun plus(other: KeySym): Chord = Chord(listOf(this, other))

    override fun toString(): String = keysym

    public companion object {
        /** keysym 名の文字。 */
        private val NAME = Regex("[A-Za-z0-9_]+")

        /** よく使われる別名を X11 の keysym 名へ寄せる。 */
        private val ALIASES = mapOf("Enter" to "Return", "Esc" to "Escape", "Space" to "space")

        /** F1。 */
        public val F1: KeySym = KeySym("F1")

        /** F2（スクリーンショット）。 */
        public val F2: KeySym = KeySym("F2")

        /** F3（デバッグ画面）。 */
        public val F3: KeySym = KeySym("F3")

        /** F4。 */
        public val F4: KeySym = KeySym("F4")

        /** F5（視点の切り替え）。 */
        public val F5: KeySym = KeySym("F5")

        /** F6。 */
        public val F6: KeySym = KeySym("F6")

        /** F7。 */
        public val F7: KeySym = KeySym("F7")

        /** F8。 */
        public val F8: KeySym = KeySym("F8")

        /** F9。 */
        public val F9: KeySym = KeySym("F9")

        /** F10。 */
        public val F10: KeySym = KeySym("F10")

        /** F11。 */
        public val F11: KeySym = KeySym("F11")

        /** F12。 */
        public val F12: KeySym = KeySym("F12")

        /** Enter キー（keysym は Return）。 */
        public val ENTER: KeySym = KeySym("Return")

        /** Esc キー。 */
        public val ESCAPE: KeySym = KeySym("Escape")

        /** スペースキー（keysym は小文字の space）。 */
        public val SPACE: KeySym = KeySym("space")

        /** Tab キー。 */
        public val TAB: KeySym = KeySym("Tab")

        /** t（チャット欄を開く）。 */
        public val T: KeySym = KeySym("t")

        /** w（前進）。 */
        public val W: KeySym = KeySym("w")

        /** a（左へ移動）。 */
        public val A: KeySym = KeySym("a")

        /** s（後退）。 */
        public val S: KeySym = KeySym("s")

        /** d（右へ移動。F3 + D でチャットを消す）。 */
        public val D: KeySym = KeySym("d")

        /** e（インベントリを開く）。 */
        public val E: KeySym = KeySym("e")

        /** q（手に持ったアイテムを捨てる）。 */
        public val Q: KeySym = KeySym("q")

        /** f（オフハンドと持ち替える）。 */
        public val F: KeySym = KeySym("f")

        /** 左の Shift（スニーク。keysym は Shift_L）。 */
        public val SHIFT: KeySym = KeySym("Shift_L")

        /** 左の Ctrl（ダッシュ。keysym は Control_L）。 */
        public val CONTROL: KeySym = KeySym("Control_L")

        /** BackSpace。 */
        public val BACKSPACE: KeySym = KeySym("BackSpace")

        /** 上矢印。 */
        public val UP: KeySym = KeySym("Up")

        /** 下矢印。 */
        public val DOWN: KeySym = KeySym("Down")

        /** 左矢印。 */
        public val LEFT: KeySym = KeySym("Left")

        /** 右矢印。 */
        public val RIGHT: KeySym = KeySym("Right")

        /** 1（ホットバーのスロット 0）。 */
        public val DIGIT_1: KeySym = KeySym("1")

        /** 2（ホットバーのスロット 1）。 */
        public val DIGIT_2: KeySym = KeySym("2")

        /** 3（ホットバーのスロット 2）。 */
        public val DIGIT_3: KeySym = KeySym("3")

        /** 4（ホットバーのスロット 3）。 */
        public val DIGIT_4: KeySym = KeySym("4")

        /** 5（ホットバーのスロット 4）。 */
        public val DIGIT_5: KeySym = KeySym("5")

        /** 6（ホットバーのスロット 5）。 */
        public val DIGIT_6: KeySym = KeySym("6")

        /** 7（ホットバーのスロット 6）。 */
        public val DIGIT_7: KeySym = KeySym("7")

        /** 8（ホットバーのスロット 7）。 */
        public val DIGIT_8: KeySym = KeySym("8")

        /** 9（ホットバーのスロット 8）。 */
        public val DIGIT_9: KeySym = KeySym("9")

        /** /（コマンド入力でチャット欄を開く）。 */
        public val SLASH: KeySym = KeySym("slash")

        /** Enter→Return, Esc→Escape, Space→space の別名を適用して作る。 */
        public fun of(name: String): KeySym = KeySym(ALIASES[name] ?: name)
    }
}
