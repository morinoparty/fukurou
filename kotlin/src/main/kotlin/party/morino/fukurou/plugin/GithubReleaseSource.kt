package party.morino.fukurou.plugin

/**
 * GitHub のリリースのアセット。
 *
 * @property repository owner/name
 * @property tag リリースのタグ
 * @property asset アセットのファイル名
 */
public data class GithubReleaseSource(val repository: String, val tag: String, val asset: String) : PluginSource
