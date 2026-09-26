package party.morino.fukurou.engine.paper.capability

import party.morino.fukurou.spi.plugin.ResolvedPlugin

/**
 * サーバーログの行をプラグインに結び付けるための情報（server/plugin_checks.py:29-60）。純粋。
 *
 * @property name plugin.yml / paper-plugin.yml の name
 * @property fileName plugins/ に置いた jar のファイル名（読み込み失敗のログはファイル名で出る）
 * @property logPrefix plugin.yml の prefix。設定されていると、そのプラグインのログは [<prefix>] で出る
 */
internal data class PluginIdentity(
    val name: String,
    val fileName: String,
    val logPrefix: String? = null,
) {
    /** Bukkit / Paper は名前の空白を _ に置き換えて扱う。 */
    val logName: String get() = name.replace(" ", "_")

    /** 有効化の開始時に出るログ。版は必須なので、名前の後には必ず " v" が続く。 */
    val enablingMarker: String get() = "[${logPrefix ?: logName}] Enabling $logName v"

    /** ファイル名の完全一致（前後が名前の一部になる文字でない）。ExampleCore.jar の行を Core.jar と取り違えない。 */
    private val fileToken: Regex = Regex("(?<![\\w.-])${Regex.escape(fileName)}(?![\\w.-])")

    /**
     * エラー行がこのプラグイン自身についてのものか。
     *
     * 依存先の名前や、名前を部分として含む別プラグイン（Core と ExampleCore など）の行を
     * 取り違えないよう、ファイル名の完全一致か「enabling <名前> v」でだけ結び付ける。
     */
    fun ownsError(line: String): Boolean = "enabling $logName v" in line || fileToken.containsMatchIn(line)

    companion object {
        /** 解決済みのプラグインから作る。記述子が無い jar はファイル名から .jar を除いたものを名前とみなす。 */
        fun of(plugin: ResolvedPlugin): PluginIdentity =
            PluginIdentity(
                name = plugin.descriptorName ?: plugin.file.removeSuffix(".jar"),
                fileName = plugin.file,
                logPrefix = plugin.descriptorPrefix,
            )
    }
}
