package party.morino.fukurou.player

/**
 * 同時に押すキーの組。xdotool には "F3+d" のように + でつないで渡す。
 *
 * @property keys 押すキー（押す順）
 */
@JvmInline
public value class Chord(public val keys: List<KeySym>) {
    /** キーを 1 つ足した組。 */
    public operator fun plus(other: KeySym): Chord = Chord(keys + other)

    /** ステップのラベルと xdotool の引数に使う形（"F3+d"）。 */
    override fun toString(): String = keys.joinToString("+") { it.keysym }
}
