package party.morino.fukurou.result.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** run（バージョン）単位の結果。 */
@Serializable
public enum class RunStatus {
    /** 全テストが passed / skipped。 */
    @SerialName("passed")
    PASSED,

    /** いずれかのテストが failed / error。 */
    @SerialName("failed")
    FAILED,

    /** インフラの失敗（run.failure）がテストの実行を妨げた。 */
    @SerialName("error")
    ERROR,
}
