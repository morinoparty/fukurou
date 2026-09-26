package party.morino.fukurou.result.model.run

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.enums.RunFailurePhase

/**
 * run 全体を止めたインフラの失敗。
 *
 * @property phase 段階
 * @property message メッセージ
 */
@Serializable
public data class RunFailure(val phase: RunFailurePhase, val message: String)
