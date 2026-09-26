package party.morino.fukurou.spi.plugin

import java.nio.file.Path

/**
 * エンジンが解決したプラグイン（ダウンロード済み + 中身を検査済み）。
 *
 * @property path jar の実ファイル
 * @property file サーバーの plugins/ に置くファイル名
 * @property sha256 jar の sha256
 * @property role "under-test" または "dependency"
 * @property source 取得元（"github:<repo>@<tag>/<asset>" など）
 * @property descriptorName plugin.yml / paper-plugin.yml の name
 * @property descriptorVersion 同 version（文字列のまま）
 * @property descriptorPrefix 同 prefix
 * @property classFileMajor クラスファイルの最大の major（META-INF/versions/ は除く）
 */
public data class ResolvedPlugin(
    val path: Path,
    val file: String,
    val sha256: String,
    val role: String,
    val source: String?,
    val descriptorName: String?,
    val descriptorVersion: String?,
    val descriptorPrefix: String?,
    val classFileMajor: Int?,
)
