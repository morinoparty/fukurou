package party.morino.fukurou.plugin

/**
 * GitHub のリリースのアセット（dependencies.py:128-149）。
 *
 * @property repository owner/name
 * @property tag リリースのタグ
 * @property asset アセットのファイル名
 */
public data class GithubReleaseSource(val repository: String, val tag: String, val asset: String) : PluginSource
