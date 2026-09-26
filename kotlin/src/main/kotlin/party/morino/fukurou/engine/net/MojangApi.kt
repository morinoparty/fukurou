package party.morino.fukurou.engine.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import party.morino.fukurou.error.SetupException

/**
 * Mojang のバージョンマニフェストと、バージョンごとの必要な Java（mojang.py:47-60）。
 *
 * マニフェストはインスタンスごとに 1 回だけ取得し、バージョンごとの結果も覚えておく。
 *
 * @property http 取得に使う HTTP
 * @property manifestUrl version_manifest_v2.json の URL（テストでは手元のサーバー）
 */
internal class MojangApi(
    private val http: Http = Http(),
    private val manifestUrl: String = MOJANG_VERSION_MANIFEST_URL,
) {
    /** 取得と記録を直列にするロック。 */
    private val mutex = Mutex()

    /** 取得済みのマニフェスト。 */
    private var manifest: JsonObject? = null

    /** バージョンごとの Java の major 番号。 */
    private val javaMajors = mutableMapOf<String, Int>()

    /** そのバージョンの公式クライアント・サーバーが要求する Java の major 番号。 */
    suspend fun javaMajor(versionId: String): Int = mutex.withLock {
        javaMajors[versionId]?.let { return@withLock it }
        val details = http.getJson(versionUrl(versionId))
        // javaVersion が無いのは 1.16 以前だけで、fukurou の対象外だが念のため Java 8 とみなす
        val major = parseJavaMajor(details) ?: DEFAULT_JAVA_MAJOR
        javaMajors[versionId] = major
        major
    }

    /** マニフェストのリリース版の id を新しい順（マニフェストの並び順）で返す。 */
    suspend fun releaseIds(): List<String> = mutex.withLock {
        versions(loadManifest()).filter { it.string("type") == "release" }.mapNotNull { it.string("id") }
    }

    /** versionId の詳細 JSON の URL。マニフェストに無ければ入力の誤りとして SetupException。 */
    private suspend fun versionUrl(versionId: String): String {
        val version = versions(loadManifest()).firstOrNull { it.string("id") == versionId }
            ?: throw SetupException("$versionId is not a Minecraft version in the Mojang version manifest")
        return version.string("url") ?: throw SetupException("$versionId has no details url in the Mojang version manifest")
    }

    /** マニフェストを 1 回だけ取得する（呼び出し側が mutex を持っている）。 */
    private suspend fun loadManifest(): JsonObject =
        manifest ?: ((http.getJson(manifestUrl) as? JsonObject)
            ?: throw SetupException("unexpected response from $manifestUrl: not a JSON object")).also { manifest = it }

    /** マニフェストの versions の各要素。 */
    private fun versions(manifest: JsonObject): List<JsonObject> =
        (manifest["versions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** JSON のオブジェクトからスカラーを文字列として読む。 */
    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Mojang のバージョンマニフェスト。 */
        const val MOJANG_VERSION_MANIFEST_URL: String = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        /** javaVersion が無いバージョンの Java。 */
        const val DEFAULT_JAVA_MAJOR: Int = 8

        /** バージョンの詳細 JSON から javaVersion.majorVersion を読む。無ければ null。 */
        fun parseJavaMajor(details: JsonElement): Int? =
            (((details as? JsonObject)?.get("javaVersion") as? JsonObject)?.get("majorVersion") as? JsonPrimitive)?.intOrNull
    }
}
