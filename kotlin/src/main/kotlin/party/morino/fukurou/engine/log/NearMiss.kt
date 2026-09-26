package party.morino.fukurou.engine.log

/**
 * 待ちの時間切れのメッセージに添える「惜しい行」のヒント。
 *
 * パターンのうち必ずそのまま現れるはずの最長のリテラル断片を取り出し、それと編集距離 1 だけ違う部分を含む行を探す。
 * `:thinking-face:` を待っていてログには `:thinking_face:` が出ている、のような取り違えを一目でわかるようにする。
 */
internal object NearMiss {
    /** これ以上長いパターンは調べない（組み立てたパターンで断片の意味が薄く、探索も重いため）。 */
    const val MAX_PATTERN_LENGTH: Int = 200

    /** これより短い断片では、たまたま 1 文字違いの行が多すぎてヒントにならない。 */
    const val MIN_FRAGMENT_LENGTH: Int = 4

    /**
     * lines のうち惜しい行があれば、`Hint: 1 line is a near miss (differs only in '_' vs '-').` の形のヒントを返す。
     *
     * @param pattern 待っていた正規表現
     * @param lines 照合したログの行
     * @return ヒントの 1 行。惜しい行が無い・パターンが長すぎる・断片が短すぎるときは null
     */
    fun hint(pattern: Regex, lines: List<String>): String? {
        if (pattern.pattern.length >= MAX_PATTERN_LENGTH) return null
        val fragment = longestLiteral(pattern.pattern) ?: return null
        // 大文字小文字を無視するパターンなら、比較も小文字どうしで行う
        val ignoreCase = RegexOption.IGNORE_CASE in pattern.options
        val needle = if (ignoreCase) fragment.lowercase() else fragment
        val misses = lines.mapNotNull { line ->
            val haystack = (if (ignoreCase) line.lowercase() else line).trimEnd('\n', '\r')
            // 断片がそのまま含まれる行は、断片以外の部分が違うので「惜しい」ではない
            if (haystack.contains(needle)) null else findEdit(haystack, needle)
        }
        if (misses.isEmpty()) return null
        val count = if (misses.size == 1) "1 line is a near miss" else "${misses.size} lines are near misses"
        // すべて同じ 1 文字の置き換えなら、どの文字どうしかを示す（ログ側の文字を先に書く）
        val first = misses.first()
        val detail = if (first.isNotEmpty() && misses.all { it == first }) {
            "differs only in $first"
        } else {
            "differs by one character"
        }
        return "Hint: $count ($detail)."
    }

    /**
     * 正規表現のソースから、一致する文字列に必ずそのまま現れる最長のリテラル断片を取り出す。
     *
     * @param source 正規表現のソース
     * @return MIN_FRAGMENT_LENGTH 文字以上の最長の断片。無ければ null
     */
    fun longestLiteral(source: String): String? {
        val fragments = mutableListOf<String>()
        val run = StringBuilder()
        // 断片を区切る。量指定子の付いた文字は省略されうるので、呼び出し側で先に落とす
        fun flush() {
            if (run.isNotEmpty()) fragments.add(run.toString())
            run.setLength(0)
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '\\' -> {
                    val next = source.getOrNull(i + 1)
                    if (next == null || next.isLetterOrDigit()) {
                        // \d \w \b や後方参照はリテラルではない
                        flush()
                    } else {
                        // \. \/ \[ などはその文字そのもの
                        run.append(next)
                    }
                    i += 2
                    continue
                }
                '[' -> {
                    // 文字クラスは 1 文字に決まらないので、閉じ括弧まで読み飛ばす
                    flush()
                    i = skipClass(source, i)
                    continue
                }
                '(' -> {
                    flush()
                    // (?: (?<name> (?= (?i) などの印は断片に含めない
                    if (source.getOrNull(i + 1) == '?') {
                        i += 2
                        while (i < source.length && source[i] !in GROUP_HEADER_END) i++
                    }
                }
                '*', '?' -> {
                    // 直前の 1 文字は 0 回かもしれない
                    if (run.isNotEmpty()) run.setLength(run.length - 1)
                    flush()
                }
                '{' -> {
                    // {0,3} なども 0 回がありうるので直前の文字を落とし、閉じ括弧まで読み飛ばす
                    if (run.isNotEmpty()) run.setLength(run.length - 1)
                    flush()
                    while (i < source.length && source[i] != '}') i++
                }
                '+', ')', '|', '^', '$', '.' -> flush()
                else -> run.append(c)
            }
            i++
        }
        flush()
        return fragments.maxByOrNull { it.length }?.takeIf { it.length >= MIN_FRAGMENT_LENGTH }
    }

    /** start の [ に対応する ] の次の位置。 */
    private fun skipClass(source: String, start: Int): Int {
        var i = start + 1
        // [] や [^] の直後の ] はクラスの中身
        if (source.getOrNull(i) == '^') i++
        if (source.getOrNull(i) == ']') i++
        while (i < source.length && source[i] != ']') {
            if (source[i] == '\\') i++
            i++
        }
        return i + 1
    }

    /**
     * haystack に needle と編集距離 1 の部分があるか調べる。
     *
     * @return 見つからなければ null。1 文字の置き換えなら `'ログ側の文字' vs 'パターン側の文字'`、挿入・削除なら空文字列
     */
    private fun findEdit(haystack: String, needle: String): String? {
        val n = needle.length
        // 置き換え: 同じ長さの窓で 1 文字だけ違う
        for (start in 0..haystack.length - n) {
            var diff = -1
            var count = 0
            for (k in 0 until n) {
                if (haystack[start + k] != needle[k]) {
                    diff = k
                    if (++count > 1) break
                }
            }
            if (count == 1) return "'${haystack[start + diff]}' vs '${needle[diff]}'"
        }
        // 挿入・削除: 1 文字長い／短い窓から 1 文字除くと一致する
        for (len in intArrayOf(n + 1, n - 1)) {
            if (len <= 0) continue
            for (start in 0..haystack.length - len) {
                if (oneInsertApart(haystack.substring(start, start + len), needle)) return ""
            }
        }
        return null
    }

    /** a と b の長さが 1 違い、長い方から 1 文字除くと短い方になるか。 */
    private fun oneInsertApart(a: String, b: String): Boolean {
        val (long, short) = if (a.length > b.length) a to b else b to a
        var i = 0
        while (i < short.length && long[i] == short[i]) i++
        return long.substring(i + 1) == short.substring(i)
    }

    // (? の後、グループの印が終わる文字
    private val GROUP_HEADER_END = setOf(':', '>', '=', '!', ')')
}
