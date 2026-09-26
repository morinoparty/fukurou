package party.morino.fukurou.result.model.step

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ステップがどの層から来たか。 */
@Serializable
public enum class StepPhase {
    /** beforeEach（拡張の setUp）。 */
    @SerialName("beforeEach")
    BEFORE_EACH,

    /** fixture { } の中。 */
    @SerialName("fixture")
    FIXTURE,

    /** テスト本体。 */
    @SerialName("test")
    TEST,
}
