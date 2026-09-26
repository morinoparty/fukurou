package party.morino.fukurou.engine.plugin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.net.Downloader
import party.morino.fukurou.engine.net.GithubReleases
import party.morino.fukurou.engine.net.Sha256
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.plugin.GithubReleaseSource
import party.morino.fukurou.plugin.LocalJarSource
import party.morino.fukurou.plugin.PluginSource
import party.morino.fukurou.plugin.SystemPropertySource
import party.morino.fukurou.plugin.UrlJarSource
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 宣言したプラグインの取得元を、ダウンロード済み・検査済みの [ResolvedPlugin] にする（dependencies.py:110-126、plugins.py:69）。
 *
 * Fukurou インスタンスごとに 1 つ使う。sha256 の無い URL はインスタンスごとに 1 回だけ取り直す。
 *
 * @property config 設定（fukurou.plugin.<key> と GITHUB_TOKEN）
 * @property downloader ダウンロードに使う
 * @property githubReleases GitHub のリリースの取得に使う
 */
internal class PluginResolver(
    private val config: FukurouConfig,
    private val downloader: Downloader,
    private val githubReleases: GithubReleases = GithubReleases(
        config.workDir.resolve("cache").resolve("dependencies").resolve("github"),
        downloader,
    ),
) {
    /** URL の jar のキャッシュ（<workDir>/cache/dependencies/url）。 */
    private val urlCache: Path = config.workDir.resolve("cache").resolve("dependencies").resolve("url")

    /** このインスタンスで取り直した、sha256 の無い URL の保存先。 */
    private val fetchedUrls = mutableMapOf<String, Path>()

    /** fetchedUrls を守るロック。 */
    private val mutex = Mutex()

    /**
     * 宣言順に解決する。
     *
     * @param declared role（"under-test" / "dependency"）と取得元の組（PluginSetBuilder.declared）
     */
    suspend fun resolve(declared: List<Pair<String, PluginSource>>): List<ResolvedPlugin> =
        declared.map { (role, source) -> resolve(role, source) }

    /** 1 つの取得元を解決する。 */
    suspend fun resolve(role: String, source: PluginSource): ResolvedPlugin {
        val (path, file, origin) = when (source) {
            is LocalJarSource -> Triple(requireJar(source.path, "plugin jar"), source.path.fileName.toString(), null)
            is SystemPropertySource -> {
                // 未設定を黙って通すとプラグイン無しでテストが走るので、渡し方を示して止める
                val path = config.plugins[source.key] ?: throw SetupException(
                    "fukurou.plugin.${source.key} is not set; pass -Pfukurou.plugin.${source.key}=<jar> " +
                        "(MineStamp's gameTest task sets it from shadowJar)",
                )
                Triple(requireJar(path, "fukurou.plugin.${source.key}"), path.fileName.toString(), null)
            }
            is UrlJarSource -> {
                val file = urlFileName(source.url)
                Triple(fetchUrl(source, file), file, source.url)
            }
            is GithubReleaseSource -> Triple(
                githubReleases.asset(source.repository, source.tag, source.asset, config.githubToken),
                source.asset,
                "github:${source.repository}@${source.tag}/${source.asset}",
            )
        }
        val inspection = PluginJarInspector.inspect(path)
        return ResolvedPlugin(
            path = path,
            file = file,
            sha256 = inspection.sha256,
            role = role,
            source = origin,
            descriptorName = inspection.name,
            descriptorVersion = inspection.version,
            descriptorPrefix = inspection.prefix,
            classFileMajor = inspection.classFileMajor,
        )
    }

    /** sha256 があれば内容で決まるキャッシュを使い、無ければインスタンスごとに 1 回ダウンロードし直す（内容が変わりうるため）。 */
    private suspend fun fetchUrl(source: UrlJarSource, file: String): Path {
        val sha = source.sha256?.lowercase()
        if (sha != null) {
            return downloader.download(source.url, urlCache.resolve(sha).resolve(file), sha)
        }
        return mutex.withLock {
            fetchedUrls.getOrPut(source.url) {
                downloader.download(source.url, urlCache.resolve(shortHash(source.url)).resolve(file))
            }
        }
    }

    /** path がファイルとして存在することを確かめる。 */
    private fun requireJar(path: Path, what: String): Path {
        if (!Files.isRegularFile(path)) throw SetupException("$what $path does not exist")
        return path
    }

    companion object {
        /** ファイル名として安全な文字だけを残す。 */
        private val UNSAFE_PATH_CHARS = Regex("[^A-Za-z0-9._-]")

        /**
         * URL のパスの末尾をファイル名にする（dependencies.py:45 file_name）。
         *
         * Paper は拡張子が .jar のファイルしか読み込まないため、.jar で終わらない場合は URL から決まる名前にする。
         */
        fun urlFileName(url: String): String {
            val rawPath = runCatching { URI.create(url).rawPath }.getOrNull().orEmpty()
            // パーセントエンコードを戻してから、使えない文字を _ にする（+ は空白にしない）
            val last = URLDecoder.decode(rawPath.substringAfterLast('/').replace("+", "%2B"), Charsets.UTF_8)
            val name = UNSAFE_PATH_CHARS.replace(last, "_")
            return if (name.endsWith(".jar") && name != ".jar") name else "dependency-${shortHash(url)}.jar"
        }

        /** URL の sha256 の先頭 16 文字（キャッシュのディレクトリ名）。 */
        fun shortHash(text: String): String = Sha256.of(text).take(16)
    }
}
