package party.morino.fukurou.result.event

import party.morino.fukurou.result.model.enums.TestFailurePhase
import party.morino.fukurou.result.model.enums.TestStatus

/**
 * テスト 1 件の結末（StatusMapper の出力、§6.4）。
 *
 * @property status 結果
 * @property failurePhase 失敗の段階（passed / skipped では null）
 * @property message 失敗のメッセージ
 * @property provisionalStepId 失敗したステップの仮 id。記録係が最終的な添字に付け替える
 * @property skipReason skipped の理由
 */
internal data class TestOutcome(
    val status: TestStatus,
    val failurePhase: TestFailurePhase? = null,
    val message: String? = null,
    val provisionalStepId: Long? = null,
    val skipReason: String? = null,
)
