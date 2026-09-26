package party.morino.fukurou.engine.paper.capability

/**
 * ある時点のサーバーログから読み取った、プラグインの状態（server/plugin_checks.py:63-80）。純粋。
 *
 * @property plugins 確認するプラグイン
 * @property enabled plugins と同じ順の、有効化できたか
 * @property errors ERROR_MARKERS を含む行（前後の空白を除いたもの）
 */
internal class PluginLogState private constructor(
    val plugins: List<PluginIdentity>,
    val enabled: List<Boolean>,
    val errors: List<String>,
) {
    /** 終わったか。エラーが出たか、全プラグインが有効になった。 */
    fun done(): Boolean = errors.isNotEmpty() || enabled.all { it }

    /** ファイル名 → 有効化できたか。 */
    fun enabledByFile(): Map<String, Boolean> = plugins.zip(enabled).associate { (plugin, ok) -> plugin.fileName to ok }

    /**
     * 失敗の説明（check_plugins の problems）。空なら問題なし。
     * エラー行で原因が分かるプラグインは、重ねて「有効化されなかった」とは書かない。
     */
    fun problems(): List<String> =
        errors + plugins.zip(enabled)
            .filter { (plugin, ok) -> !ok && errors.none(plugin::ownsError) }
            .map { (plugin, _) -> "${plugin.name} was not enabled (no '${plugin.enablingMarker}' in the server log)" }

    /** problems を 1 行にしたもの（Python の PluginCheckError の文面）。問題が無ければ null。 */
    fun failureMessage(): String? = problems().takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "plugin check failed: ")

    companion object {
        /** どのプラグインであっても、これらがサーバーログに出たら読み込みか有効化に失敗している。 */
        val ERROR_MARKERS: List<String> = listOf(
            "Could not load plugin",
            "Could not load '",
            "Error occurred while enabling",
            "Unsupported class file major version",
            "Failed to remap plugin",
        )

        /** log から状態を読む。 */
        fun read(log: String, plugins: List<PluginIdentity>): PluginLogState {
            val errors = log.lines().filter { line -> ERROR_MARKERS.any { it in line } }.map { it.trim() }
            // 有効化の開始ログの後に有効化の失敗が出ることもあるため、自身のエラー行があれば無効とみなす
            val enabled = plugins.map { plugin -> plugin.enablingMarker in log && errors.none(plugin::ownsError) }
            return PluginLogState(plugins, enabled, errors)
        }
    }
}
