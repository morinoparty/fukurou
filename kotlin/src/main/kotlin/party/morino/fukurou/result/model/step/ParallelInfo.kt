package party.morino.fukurou.result.model.step

import kotlinx.serialization.Serializable

/**
 * parallel ブロックの中のステップの位置。同じ block のステップは同時に実行された。
 *
 * @property block テスト内の parallel ブロックの通し番号（0 始まり）
 * @property lane ブロックの中のレーンの番号（0 始まり）
 */
@Serializable
public data class ParallelInfo(val block: Int, val lane: Int)
