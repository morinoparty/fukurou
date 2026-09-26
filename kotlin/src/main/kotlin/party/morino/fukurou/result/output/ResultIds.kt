package party.morino.fukurou.result.output

/**
 * result.json の id とテストの id の規則（§6.3、§5.5、build_manifest.py の SAFE_ID / SAFE_TEST_ID）。
 *
 * すべて純粋関数。JVM 内での重複の管理（取った id の集合）は呼び出し側（LeaseRegistry）が持つ。
 */
internal object ResultIds {
    /** label の規則。artifact 名の末尾から TRAILING_RUN_ID で拾えるよう、小文字の kebab case に限る。 */
    val LABEL: Regex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    /** label の最大の長さ。artifact 名やパスが長くなりすぎないようにする。 */
    const val LABEL_MAX_LENGTH: Int = 40

    /** テストの id の規則。ビューアのルートと tests/<id>/ のパスになるので契約と同じ規則にする。 */
    val TEST_ID: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]*$")

    /** id に使えない文字。 */
    private val UNSAFE_TEST_ID_CHARS = Regex("[^A-Za-z0-9_.-]")

    /** 連続した "-"。置き換えの結果の "---" を 1 つにまとめる。 */
    private val DASH_RUN = Regex("-{2,}")

    /** 先頭の英数字以外。TEST_ID は英数字で始まる必要がある。 */
    private val LEADING_NON_ALNUM = Regex("^[^A-Za-z0-9]+")

    /** 大文字の並びの後に大文字 + 小文字が続く位置（"HTTPServer" → "HTTP-Server"）。 */
    private val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")

    /** 小文字・数字の後に大文字が続く位置（"StampArena" → "Stamp-Arena"）。 */
    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

    /** kebab case に使えない文字。 */
    private val NON_KEBAB = Regex("[^a-z0-9]+")

    /**
     * run の id（"<type>-<version>-<label>"。例: paper-26.3-stamp-arena）。
     *
     * @throws IllegalArgumentException label が規則に合わない
     */
    fun runId(typeId: String, version: String, label: String): String {
        // 不正な label はファイル名やビューアの集計を壊すので、書き出す前に止める
        checkLabel(label)
        return "$typeId-$version-$label"
    }

    /**
     * label を検査する。
     *
     * @throws IllegalArgumentException 規則（小文字の kebab case、40 文字以内）に合わない
     */
    fun checkLabel(label: String): String {
        require(label.length <= LABEL_MAX_LENGTH) { "label '$label' is longer than $LABEL_MAX_LENGTH characters" }
        require(LABEL.matches(label)) { "label '$label' must match ${LABEL.pattern} (lower-case kebab case)" }
        return label
    }

    /**
     * クラスの単純名を kebab case にする（拡張の既定の label。StampArena → stamp-arena）。
     *
     * @throws IllegalArgumentException 結果が label の規則に合わない（英数字を含まない名前など）
     */
    fun kebab(simpleName: String): String {
        // 略語の切れ目 → 単語の切れ目の順に "-" を入れ、英数字以外をまとめて "-" にする
        val separated = simpleName.replace(ACRONYM_BOUNDARY, "$1-$2").replace(CAMEL_BOUNDARY, "$1-$2")
        val kebab = separated.lowercase().replace(NON_KEBAB, "-").trim('-')
        // 長すぎる名前は切り詰める。切った位置の "-" は残さない
        val label = kebab.take(LABEL_MAX_LENGTH).trimEnd('-')
        return checkLabel(label)
    }

    /**
     * JVM 内で既に使った id と重ならない id（2 つ目から -2, -3, …）。
     *
     * @param candidate 希望する id
     * @param taken 既に使った id
     */
    fun uniqueRunId(candidate: String, taken: Set<String>): String {
        if (candidate !in taken) return candidate
        // 同じ id の run ディレクトリが上書きし合わないよう、空いている最小の番号を付ける
        return generateSequence(2) { it + 1 }.map { "$candidate-$it" }.first { it !in taken }
    }

    /**
     * メソッド名からテストの id を作る（§5.5 の 2）。使えない文字は "-" にし、何も残らなければ "test"。
     */
    fun sanitizeTestId(raw: String): String {
        val replaced = raw.replace(UNSAFE_TEST_ID_CHARS, "-").replace(DASH_RUN, "-")
        // 先頭は英数字でなければならないので、それ以外を落とす
        return replaced.replace(LEADING_NON_ALNUM, "").ifEmpty { "test" }
    }

    /**
     * @GameTestId のように利用者が指定した id を検査する。
     *
     * @throws IllegalArgumentException 規則に合わない
     */
    fun checkTestId(id: String): String {
        require(TEST_ID.matches(id)) { "test id '$id' must match ${TEST_ID.pattern}" }
        // "." と ".." はパスとして特別な意味を持つので使わせない（build_manifest.py の SAFE_ID と同じ）
        require(id != "." && id != "..") { "test id '$id' is not usable as a directory name" }
        return id
    }

    /** テンプレートの n 回目の実行の id（§5.5 の 3）。 */
    fun invocationId(id: String, invocation: Int): String = "$id-$invocation"

    /**
     * 1 つの result の中で重なった id を "<ClassSimpleName>.<id>" にする（§5.5 の 4）。計画順に決まるので再現できる。
     *
     * @param tests 計画順の (クラスの単純名, id)
     * @return 同じ順序の、重なりの無い id
     */
    fun dedupeTestIds(tests: List<Pair<String, String>>): List<String> {
        // 2 回以上現れる id だけをクラス名で区別する（1 回だけの id は変えない）
        val counts = tests.groupingBy { it.second }.eachCount()
        val used = mutableSetOf<String>()
        return tests.map { (className, id) ->
            val base = if ((counts[id] ?: 0) > 1) "$className.$id" else id
            // 同じクラスに同じ id が 2 つある（テンプレートの外で名前が重なった）場合も潰さないよう番号を付ける
            val unique = uniqueRunId(base, used)
            used += unique
            unique
        }
    }
}
