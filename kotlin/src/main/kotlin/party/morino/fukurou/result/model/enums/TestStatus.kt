package party.morino.fukurou.result.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** テスト単位の結果。failed はステップの失敗（プラグインのバグ候補）、error はハーネス側の失敗。 */
@Serializable
public enum class TestStatus {
    /** 成功。 */
    @SerialName("passed")
    PASSED,

    /** ステップの失敗。 */
    @SerialName("failed")
    FAILED,

    /** ハーネス側の失敗。 */
    @SerialName("error")
    ERROR,

    /** 実行しなかった（skipReason に理由）。 */
    @SerialName("skipped")
    SKIPPED,
}
