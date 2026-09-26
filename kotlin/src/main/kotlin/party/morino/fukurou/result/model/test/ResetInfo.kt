package party.morino.fukurou.result.model.test

import kotlinx.serialization.Serializable

/**
 * テストの前にハーネスが行ったリセット（ステップではない）。
 *
 * @property durationMs 所要時間
 * @property error 失敗したコマンドとその応答。あればテストは error（phase reset）
 */
@Serializable
public data class ResetInfo(val durationMs: Long? = null, val error: String? = null)
