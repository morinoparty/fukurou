package party.morino.fukurou.result.model.test

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ログファイルのうちテスト 1 件の間に書かれた行。1 始まりで両端を含む。
 *
 * @property fromLine 最初の行（JSON のキーは from）
 * @property to 最後の行
 */
@Serializable
public data class LogRange(
    @SerialName("from") val fromLine: Int,
    val to: Int,
)
