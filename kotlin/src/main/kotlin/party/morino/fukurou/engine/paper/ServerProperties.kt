package party.morino.fukurou.engine.paper

/** server.properties の既定値と、利用者の上書き指定との合成（server/properties.py）。純粋。 */
internal object ServerProperties {
    /**
     * 再現性のためのフラットワールドなど、テスト用サーバーの既定値。
     * 値は server.properties の書式のまま（: は \: にエスケープ済み）で持つ。
     */
    val DEFAULT_PROPERTIES: Map<String, String> = linkedMapOf(
        // オフラインモードのため、クライアントは Microsoft アカウント無しで参加できる
        "online-mode" to "false",
        "enforce-secure-profile" to "false",
        // 26.3 から white-list の既定値が true になり、テスト用のプレイヤーが参加できなくなるため明示的に切る
        "white-list" to "false",
        "enforce-whitelist" to "false",
        // 平坦なワールドにして背景の地形による写り方の揺れを減らす
        "level-type" to "minecraft\\:flat",
        // 既定の "{}" だとレイヤー無しとしてエラーになるため明示する（地表は y=-61、足元は y=-60）
        "generator-settings" to (
            "{\"layers\"\\:[{\"block\"\\:\"minecraft\\:bedrock\",\"height\"\\:1}," +
                "{\"block\"\\:\"minecraft\\:dirt\",\"height\"\\:2},{\"block\"\\:\"minecraft\\:grass_block\",\"height\"\\:1}]," +
                "\"biome\"\\:\"minecraft\\:plains\"}"
            ),
        "level-name" to "fukurou-world",
        "difficulty" to "peaceful",
        "spawn-monsters" to "false",
        "spawn-protection" to "0",
        "view-distance" to "4",
        "simulation-distance" to "4",
        "motd" to "fukurou game test",
    )

    /**
     * 合成の結果。
     *
     * @property properties 書き出す値（既定値の順、足されたキーはその後）
     * @property ignored 利用者が指定したのに無視した管理キー（指定された順、重複なし）
     */
    data class Merged(val properties: Map<String, String>, val ignored: List<String>)

    /**
     * key=value の行を読む。空行と # / ! で始まるコメント行は無視する。
     * 値はエスケープを解釈せずにそのまま保持し、書き出すときも同じ文字列を使う。形が違えば IllegalArgumentException。
     */
    fun parse(text: String): Map<String, String> {
        val properties = linkedMapOf<String, String>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachIndexed
            // 値に = を含められるよう、最初の = で分ける
            val separator = line.indexOf('=')
            require(separator > 0 && line.substring(0, separator).isNotBlank()) {
                "line ${index + 1}: expected key=value, got '$raw'"
            }
            properties[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        return properties
    }

    /**
     * 既定値に layers を順に重ね、最後に fukurou が管理するキーで上書きする。
     * ポートや RCON の設定を変えられるとテストを制御できなくなるため、managed は必ず優先する。
     */
    fun merge(layers: List<Map<String, String>>, managed: Map<String, String>): Merged {
        val merged = LinkedHashMap(DEFAULT_PROPERTIES)
        val ignored = linkedSetOf<String>()
        for (layer in layers) {
            for ((key, value) in layer) {
                // 管理キーは使わず、警告のために覚えておく
                if (key in managed) ignored += key else merged[key] = value
            }
        }
        merged.putAll(managed)
        return Merged(merged, ignored.toList())
    }

    /** server.properties の本文を作る。 */
    fun render(properties: Map<String, String>): String =
        properties.entries.joinToString("") { (key, value) -> "$key=$value\n" }
}
