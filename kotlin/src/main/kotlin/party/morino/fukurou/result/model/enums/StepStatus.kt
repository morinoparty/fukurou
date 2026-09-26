package party.morino.fukurou.result.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ステップの結果。 */
@Serializable
public enum class StepStatus {
    /** 成功。 */
    @SerialName("passed")
    PASSED,

    /** 失敗。 */
    @SerialName("failed")
    FAILED,

    /** 実行しなかった。 */
    @SerialName("skipped")
    SKIPPED,
}
