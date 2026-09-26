package party.morino.fukurou.result.model.step

import kotlinx.serialization.Serializable

/**
 * repeat ブロックの何回目のステップか。Kotlin 版は repeat を記録しない（常に null）が、契約の型として持つ。
 *
 * @property block テスト内の repeat ブロックの通し番号
 * @property iteration 1 始まりの繰り返し番号
 * @property of 繰り返しの回数
 */
@Serializable
public data class RepeatInfo(val block: Int, val iteration: Int, val of: Int)
