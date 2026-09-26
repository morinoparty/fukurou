package party.morino.fukurou.plugin

/**
 * URL からダウンロードする jar。
 *
 * @property url ダウンロード元
 * @property sha256 期待する sha256。null なら Fukurou インスタンスごとに 1 回取り直す
 */
public data class UrlJarSource(val url: String, val sha256: String?) : PluginSource
