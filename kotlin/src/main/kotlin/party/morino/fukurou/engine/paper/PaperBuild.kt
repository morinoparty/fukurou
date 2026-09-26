package party.morino.fukurou.engine.paper

import kotlinx.serialization.Serializable
import party.morino.fukurou.error.SetupException

/**
 * Paper の 1 ビルド（fill v3 の応答、paper.py:28-53）。チャンネルは STABLE / BETA / ALPHA のいずれか。
 *
 * @property id ビルド番号
 * @property channel ビルドのチャンネル（大文字）
 * @property downloads ダウンロードの種類（"server:default" など）→ ダウンロード
 */
@Serializable
internal data class PaperBuild(
    val id: Int,
    val channel: String,
    val downloads: Map<String, Download> = emptyMap(),
) {
    /** サーバー本体の jar。1.20 などの Mojang マッピング版（server:mojang）は使わない。 */
    fun serverDownload(): Download =
        downloads[SERVER_DOWNLOAD_KEY] ?: throw SetupException("Paper build $id has no server jar")

    /**
     * 1 つのダウンロード。
     *
     * @property name ファイル名
     * @property checksums チェックサム
     * @property url ダウンロードの URL
     */
    @Serializable
    data class Download(val name: String, val checksums: Checksums, val url: String)

    /**
     * ダウンロードのチェックサム。
     *
     * @property sha256 SHA-256（16 進）
     */
    @Serializable
    data class Checksums(val sha256: String)

    companion object {
        /** サーバー本体の jar のキー。 */
        const val SERVER_DOWNLOAD_KEY: String = "server:default"
    }
}
