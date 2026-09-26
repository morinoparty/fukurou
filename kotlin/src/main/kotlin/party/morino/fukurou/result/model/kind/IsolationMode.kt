package party.morino.fukurou.result.model.kind

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** result.json に書く隔離方法（server.Isolation とは別の、契約の値）。 */
@Serializable
public enum class IsolationMode {
    /** テストの前にリセットする。 */
    @SerialName("reset")
    RESET,

    /** テストごとにサーバーを作り直す。 */
    @SerialName("fresh-server")
    FRESH_SERVER,
}
