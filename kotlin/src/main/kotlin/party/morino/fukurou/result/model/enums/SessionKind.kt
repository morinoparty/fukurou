package party.morino.fukurou.result.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** セッションの種類。 */
@Serializable
public enum class SessionKind {
    /** 最初の起動。 */
    @SerialName("initial")
    INITIAL,

    /** テストのための作り直し。 */
    @SerialName("fresh-server")
    FRESH_SERVER,
}
