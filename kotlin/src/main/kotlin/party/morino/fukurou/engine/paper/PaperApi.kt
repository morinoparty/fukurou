package party.morino.fukurou.engine.paper

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import party.morino.fukurou.engine.net.Http
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.server.paper.PaperChannel
import party.morino.fukurou.version.MinecraftVersion

/**
 * Paper のダウンロード API（fill v3）。
 *
 * @property http Paper が求める User-Agent を付ける HTTP
 * @property projectUrl プロジェクトの URL（テストで差し替えられるようにする）
 */
internal class PaperApi(
    private val http: Http = Http(),
    private val projectUrl: String = PROJECT_URL,
) {
    /**
     * 使うビルドを決める。build を指定したらチャンネルに関係なくそのビルド（しきい値を下回るかは呼び出し側で警告する）。
     * 指定が無ければ、threshold で許されるチャンネルのうち最も新しいビルド。
     */
    suspend fun resolve(version: MinecraftVersion, threshold: PaperChannel, build: Int?): PaperBuild {
        val base = "$projectUrl/versions/$version/builds"
        try {
            if (build != null) return decode("$base/$build", http.getJson("$base/$build"), PaperBuild.serializer())
            val url = "$base?${PaperBuildPicker.channelQuery(threshold)}"
            val builds = decode(url, http.getJson(url), BUILD_LIST)
            return PaperBuildPicker.pick(builds, threshold)
                ?: throw SetupException(PaperBuildPicker.noAcceptedBuildMessage(version, threshold))
        } catch (error: Http.NotFound) {
            // 存在しないバージョンやビルドの指定は入力の誤り
            val target = if (build != null) "build $build for $version" else "builds for $version"
            throw SetupException("Paper has no $target", error)
        }
    }

    /**
     * Paper にビルドがある全バージョン。
     * プロジェクトの versions は {"1.21": ["1.21.11", …]} のようにまとめてあるので平らにする。
     * pre-release も含むので [MinecraftVersion] にはしない。
     */
    suspend fun versionIds(): Set<String> {
        val project = http.getJson(projectUrl) as? JsonObject
            ?: throw SetupException("unexpected response from $projectUrl: not a JSON object")
        val groups = project["versions"] as? JsonObject
            ?: throw SetupException("unexpected response from $projectUrl: no versions object")
        return groups.values.flatMap { group ->
            (group as? JsonArray ?: throw SetupException("unexpected response from $projectUrl: a version group is not an array"))
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }.toSet()
    }

    /** そのバージョンの最新ビルドのチャンネル。 */
    suspend fun latestChannel(version: String): String {
        val url = "$projectUrl/versions/$version/builds/latest"
        return decode(url, http.getJson(url), PaperBuild.serializer()).channel
    }

    /**
     * そのバージョンに、threshold で許されるチャンネルのビルドが 1 つでもあるか。
     *
     * 多くは最新ビルドのチャンネルだけで決まる（1 回の問い合わせ）。最新が許されなくても古いビルドが
     * 許されるチャンネルにあり得るので、そのときは [resolve] と同じ絞り込みで一覧を確かめる。
     */
    suspend fun hasAcceptedBuild(version: String, threshold: PaperChannel): Boolean {
        if (PaperBuildPicker.channelAccepted(latestChannel(version), threshold)) return true
        val url = "$projectUrl/versions/$version/builds?${PaperBuildPicker.channelQuery(threshold)}"
        return PaperBuildPicker.pick(decode(url, http.getJson(url), BUILD_LIST), threshold) != null
    }

    /** JSON をモデルにする。形が違えば SetupException。 */
    private fun <T> decode(url: String, json: JsonElement, serializer: kotlinx.serialization.KSerializer<T>): T =
        try {
            Http.JSON.decodeFromJsonElement(serializer, json)
        } catch (error: SerializationException) {
            throw SetupException("unexpected response from $url: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw SetupException("unexpected response from $url: ${error.message}", error)
        }

    companion object {
        /** Paper のプロジェクトの URL。 */
        const val PROJECT_URL: String = "https://fill.papermc.io/v3/projects/paper"

        /** ビルド一覧（配列）の読み手。 */
        private val BUILD_LIST = kotlinx.serialization.builtins.ListSerializer(PaperBuild.serializer())
    }
}
