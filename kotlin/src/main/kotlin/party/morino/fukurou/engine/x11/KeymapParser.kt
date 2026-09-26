package party.morino.fukurou.engine.x11

/** `xmodmap -pke` の出力をキーコード → keysym の列の表にする。純粋。 */
internal object KeymapParser {
    /** "keycode  71 = F5 F5 F5 F5 …" の 1 行（右辺は空のこともある）。 */
    private val LINE = Regex("""^\s*keycode\s+(\d+)\s*=\s*(.*?)\s*$""")

    /** keysym が割り当てられていない列の名前。 */
    private const val NO_SYMBOL = "NoSymbol"

    /**
     * text を読み、キーコードの昇順の表を返す。列は group 0 level 0 から順に並ぶ。
     * NoSymbol の列は位置を保つために残すが、どの keysym とも一致しない。
     */
    fun parse(text: String): Map<Int, List<String>> {
        val table = sortedMapOf<Int, List<String>>()
        for (line in text.lineSequence()) {
            val match = LINE.matchEntire(line) ?: continue
            val keycode = match.groupValues[1].toInt()
            // 空白で区切った列。右辺が空（割り当て無し）なら空の一覧
            val columns = match.groupValues[2].split(Regex("\\s+")).filter { it.isNotEmpty() }
            table[keycode] = columns
        }
        return table
    }

    /** column が実際の keysym（NoSymbol でない）で name と一致するか。 */
    fun matches(column: String, name: String): Boolean = column != NO_SYMBOL && column == name
}
