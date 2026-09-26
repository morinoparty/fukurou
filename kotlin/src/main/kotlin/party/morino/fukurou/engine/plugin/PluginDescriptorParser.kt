package party.morino.fukurou.engine.plugin

/**
 * plugin.yml / paper-plugin.yml から、最上位の name・version・prefix のスカラーだけを読む（plugins.py:91 read_descriptor）。
 *
 * YAML のライブラリを使わない代わりに、値は数値にせず文字列のまま扱う（BaseLoader と同じく version: 1.10 は "1.10"）。
 */
internal object PluginDescriptorParser {
    /** 読むキー。 */
    private val KEYS = setOf("name", "version", "prefix")

    /** 行頭（字下げなし）の `key: value`。 */
    private val TOP_LEVEL = Regex("""^([A-Za-z0-9_-]+)\s*:(?:\s+(.*))?$""")

    /**
     * 記述ファイルの本文から、読めたキーと値を返す。空の値は含めない。
     *
     * 最上位の値が閉じていない [ や { で始まるなど、明らかに壊れていれば IllegalArgumentException。
     */
    fun parse(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        // BOM と CRLF を取り除いてから 1 行ずつ見る
        text.removePrefix("﻿").lineSequence().map { it.trimEnd('\r') }.forEach { line ->
            // 字下げされた行（入れ子のキー）とコメントは最上位のキーではない
            if (line.isEmpty() || line[0].isWhitespace() || line[0] == '#') return@forEach
            val match = TOP_LEVEL.matchEntire(line) ?: return@forEach
            val key = match.groupValues[1]
            if (key !in KEYS || key in result) return@forEach
            scalar(key, match.groupValues[2])?.let { result[key] = it }
        }
        return result
    }

    /** 値の部分をスカラーとして読む。引用符を外し、引用符の外のコメントを除く。スカラーでなければ null。 */
    private fun scalar(key: String, raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return when (value[0]) {
            '"', '\'' -> {
                val quote = value[0]
                val end = value.indexOf(quote, startIndex = 1)
                require(end > 0) { "unterminated quoted value for '$key'" }
                // 二重引用符の簡単なエスケープだけを戻す
                value.substring(1, end).let { if (quote == '"') it.replace("\\\"", "\"").replace("\\\\", "\\") else it }
                    .ifEmpty { null }
            }
            '[', '{' -> {
                // フローの列やマップはスカラーではない（Python の _optional_str も None にする）。閉じていなければ壊れている
                val close = if (value[0] == '[') ']' else '}'
                require(stripComment(value).endsWith(close)) { "unterminated flow value for '$key'" }
                null
            }
            // ブロックスカラー（| や >）は name などには使われないので読まない
            '|', '>' -> null
            else -> stripComment(value).ifEmpty { null }
        }
    }

    /** 引用符の外の「 #」から後ろ（コメント）を除く。 */
    private fun stripComment(value: String): String {
        val index = value.indexOf(" #")
        return (if (index >= 0) value.substring(0, index) else value).trim()
    }
}
