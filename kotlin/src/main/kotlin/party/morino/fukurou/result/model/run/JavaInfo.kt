package party.morino.fukurou.result.model.run

import kotlinx.serialization.Serializable

/**
 * 使った Java。
 *
 * @property server サーバーを起動した Java の major 番号（クライアントは PortableMC が選ぶ公式ランタイム）
 */
@Serializable
public data class JavaInfo(val server: Int? = null)
