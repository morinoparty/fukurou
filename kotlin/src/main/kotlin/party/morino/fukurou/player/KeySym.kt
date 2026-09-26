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

        /** よく使われる別名を X11 の keysym 名へ寄せる（player_actions.py:10-14）。 */
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

        /** d（F3 + D でチャットを消す）。 */
        public val D: KeySym = KeySym("d")

        /** /（コマンド入力でチャット欄を開く）。 */
        public val SLASH: KeySym = KeySym("slash")

        /** Enter→Return, Esc→Escape, Space→space の別名を適用して作る。 */
        public fun of(name: String): KeySym = KeySym(ALIASES[name] ?: name)
    }
}
