package party.morino.fukurou.version

/**
 * Minecraft のリリース版の番号（"1.21.11"、"26.3" など）。pre-release とスナップショットは拒否する（versions.py:141）。
 *
 * Python 版はマニフェストの並び順で前後を決めるが、ここではマニフェストなしで比べられるよう、
 * ドット区切りの各部分を整数として先頭から比べる（1.21.11 < 26.1 も正しく並ぶ）。
 *
 * @property id バージョン番号の文字列
 */
@JvmInline
public value class MinecraftVersion(public val id: String) : Comparable<MinecraftVersion> {
    init {
        // "-" を含む 1.21.11-rc3 や 25w14a のようなスナップショットはここで弾く
        require(VERSION.matches(id)) {
            "'$id' is not a Minecraft release; pre-releases and snapshots are not supported"
        }
    }

    /** ドット区切りの各部分を整数にしたもの。 */
    private val parts: List<Int> get() = id.split('.').map { it.toInt() }

    /** 各部分を先頭から比べる。足りない部分は 0 とみなす（1.21 == 1.21.0）。 */
    override fun compareTo(other: MinecraftVersion): Int {
        val mine = parts
        val theirs = other.parts
        for (i in 0 until maxOf(mine.size, theirs.size)) {
            val diff = mine.getOrElse(i) { 0 }.compareTo(theirs.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString(): String = id

    private companion object {
        /** リリース版の形。数字をドットで 2 つ以上つないだもの。 */
        private val VERSION = Regex("""\d+(\.\d+)+""")
    }
}
