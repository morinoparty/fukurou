package party.morino.fukurou.plugin

import java.nio.file.Path

/** サーバーに入れるプラグインの取得元。解決（ダウンロードと検査）はエンジンが起動時に行う。 */
public sealed interface PluginSource {
    public companion object {
        /** ローカルの jar。 */
        public fun file(path: Path): PluginSource = LocalJarSource(path)

        /** ローカルの jar（パスを文字列で）。 */
        public fun file(path: String): PluginSource = LocalJarSource(Path.of(path))

        /** URL の jar。sha256 を渡すとキャッシュのキーと検証に使う。 */
        public fun url(url: String, sha256: String? = null): PluginSource = UrlJarSource(url, sha256)

        /** GitHub のリリースのアセット。GITHUB_TOKEN があれば使う。 */
        public fun githubRelease(repository: String, tag: String, asset: String): PluginSource =
            GithubReleaseSource(repository, tag, asset)

        /** fukurou.plugin.<key> を遅延解決。未設定なら「-Pfukurou.plugin.<key>=… を渡す」旨の SetupException（黙って通さない）。 */
        public fun systemProperty(key: String): PluginSource = SystemPropertySource(key)
    }
}
