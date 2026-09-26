package party.morino.fukurou.plugin

/**
 * fukurou.plugin.<key> で渡された jar（MineStamp の gameTest タスクが shadowJar を渡す）。
 *
 * @property key fukurou.plugin. の後ろのキー
 */
public data class SystemPropertySource(val key: String) : PluginSource
