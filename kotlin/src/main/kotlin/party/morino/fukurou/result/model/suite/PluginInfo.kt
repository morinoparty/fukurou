package party.morino.fukurou.result.model.suite

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.kind.PluginRole

/**
 * サーバーに入れたプラグイン。
 *
 * @property file ファイル名
 * @property sha256 jar の sha256
 * @property name 記述子の name
 * @property version 記述子の version
 * @property role 役割
 * @property source 取得元
 * @property classFileMajor クラスファイルの最大の major
 * @property enabled サーバーログで有効化を確認できたか。確認前に失敗した場合は null
 */
@Serializable
public data class PluginInfo(
    val file: String,
    val sha256: String,
    val name: String? = null,
    val version: String? = null,
    val role: PluginRole,
    val source: String? = null,
    val classFileMajor: Int? = null,
    val enabled: Boolean? = null,
)
