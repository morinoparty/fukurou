package party.morino.fukurou.result.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** テスト 1 件の失敗の段階。beforeEach / fixture / scenario はステップの失敗（failed）、それ以外は error。 */
@Serializable
public enum class TestFailurePhase {
    /** リセット。 */
    @SerialName("reset")
    RESET,

    /** beforeEach のステップ。 */
    @SerialName("beforeEach")
    BEFORE_EACH,

    /** fixture のステップ。 */
    @SerialName("fixture")
    FIXTURE,

    /** テスト本体のステップ。 */
    @SerialName("scenario")
    SCENARIO,

    /** クライアントの終了。 */
    @SerialName("client")
    CLIENT,

    /** テストの期限切れ。 */
    @SerialName("timeout")
    TIMEOUT,
}
