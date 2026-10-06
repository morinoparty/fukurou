package party.morino.fukurou.result.model.step

import kotlinx.serialization.Serializable

/**
 * repeat ブロックの何回目のステップか（party.morino.fukurou.repeat の中のステップに付く）。
 *
 * @property block テスト内の repeat ブロックの通し番号
 * @property iteration 1 始まりの繰り返し番号
 * @property of 繰り返しの回数
 */
@Serializable
public data class RepeatInfo(val block: Int, val iteration: Int, val of: Int)
