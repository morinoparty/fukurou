package party.morino.fukurou.result.model.suite

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.kind.IsolationMode

/**
 * どのテストを選んだか。
 *
 * @property tests 選択したテスト
 * @property tags 選択したタグ
 * @property isolation 全テストに強制した隔離方法（Kotlin 版は常に null）
 * @property failFast 最初の失敗で止めるか（Kotlin 版は常に false）
 */
@Serializable
public data class SelectionInfo(
    val tests: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val isolation: IsolationMode? = null,
    val failFast: Boolean = false,
)
