package party.morino.fukurou.engine.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import party.morino.fukurou.error.SetupException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * GitHub のリリースに添付された jar の取得（dependencies.py:128-149 _fetch_github_asset）。
 *
 * dev-build のように同じタグで更新され続けるリリースもあるため、アセットの id と更新日時をキャッシュのキーにする。
 *
 * @property cacheDir アセットの保存先（<workDir>/cache/dependencies/github）
 * @property downloader リダイレクト先からのダウンロードに使う
 * @property apiUrl GitHub の API の URL（テストでは手元のサーバー）
 */
internal class GithubReleases(
    private val cacheDir: Path,
    private val downloader: Downloader,
    private val apiUrl: String = GITHUB_API_URL,
) {
    /** リリースの情報を取得する HTTP（リダイレクトは通常どおり追う）。 */
    private val api = Http()

    /** アセットの取得だけに使う、リダイレクトを追わない HTTP（トークンをリダイレクト先へ送らないため）。 */
    private val assetHttp = Http(redirect = HttpClient.Redirect.NEVER)

    /**
     * repository の tag のリリースから asset をキャッシュへ取得し、そのパスを返す。
     *
     * アセットが無ければ、リリースにあるアセットの名前を並べた SetupException。
     */
    suspend fun asset(repository: String, tag: String, asset: String, token: String?): Path {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            // 非公開リポジトリやレート制限の緩和のため、トークンがあれば付ける
            if (!token.isNullOrEmpty()) put("Authorization", "Bearer $token")
        }
        val release = api.getJson("$apiUrl/repos/$repository/releases/tags/$tag", headers)
        val assets = ((release as? JsonObject)?.get("assets") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val found = assets.firstOrNull { it.string("name") == asset }
        if (found == null) {
            val names = assets.joinToString(", ") { it.string("name") ?: "?" }.ifEmpty { "none" }
            throw SetupException("$repository@$tag has no asset named '$asset' (assets: $names)")
        }
        // id と更新日時をファイル名として安全な文字にしてキャッシュのキーにする
        val key = UNSAFE_PATH_CHARS.replace("${found.string("id")}-${found.string("updated_at")}", "_")
        val destination = cacheDir.resolve(key).resolve(asset)
        val assetUrl = found.string("url") ?: throw SetupException("$repository@$tag asset '$asset' has no API url")
        return CacheLock.withLock(destination) {
            // 同じ id と更新日時のアセットは内容が変わらないので、あればそのまま使う
            if (Files.isRegularFile(destination)) return@withLock destination
            fetchAsset(assetUrl, destination, token)
        }
    }

    /**
     * API の URL に octet-stream を要求すると、非公開リポジトリでもトークンでダウンロードできる。
     *
     * 応答は署名付き URL への 302 で、そこへはトークンを送らない（署名付き URL は Bearer を拒む。add_unredirected_header と同じ）。
     */
    private suspend fun fetchAsset(assetUrl: String, destination: Path, token: String?): Path {
        val headers = buildMap {
            put("Accept", "application/octet-stream")
            if (!token.isNullOrEmpty()) put("Authorization", "Bearer $token")
        }
        val response = assetHttp.send(assetHttp.request(assetUrl, headers), HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()
        return when {
            status in 300..399 -> {
                // 本文は使わないので閉じる
                runInterruptible(Dispatchers.IO) { response.body().close() }
                val location = response.headers().firstValue("Location").orElseThrow {
                    SetupException("download of $assetUrl was redirected without a Location header")
                }
                // 相対の Location にも対応し、リダイレクト先へはトークンを付けずに 1 回だけ取りに行く
                val target = URI.create(assetUrl).resolve(location).toString()
                downloader.download(target, destination)
            }
            // リダイレクトしないホスト（GitHub Enterprise など）は本文をそのまま保存する
            status in 200..299 -> downloader.store(response.body(), assetUrl, destination, null)
            else -> {
                runInterruptible(Dispatchers.IO) { response.body().close() }
                throw SetupException("download of $assetUrl failed with HTTP $status")
            }
        }
    }

    /** JSON のオブジェクトからスカラーを文字列として読む（数値の id もそのまま文字列にする）。 */
    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    companion object {
        /** GitHub の API。 */
        const val GITHUB_API_URL: String = "https://api.github.com"

        /** ファイル名として安全な文字だけを残す（キャッシュのディレクトリ名に使う）。 */
        val UNSAFE_PATH_CHARS: Regex = Regex("[^A-Za-z0-9._-]")
    }
}
