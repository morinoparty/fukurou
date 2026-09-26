package party.morino.fukurou.result.model.test

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.enums.TestFailurePhase

/**
 * テスト 1 件の失敗。
 *
 * @property phase 段階
 * @property message メッセージ
 * @property stepIndex 失敗したステップの steps の添字。ステップに結び付かなければ null
 */
@Serializable
public data class TestFailure(
    val phase: TestFailurePhase,
    val message: String,
    val stepIndex: Int? = null,
)
