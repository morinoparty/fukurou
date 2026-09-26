package party.morino.fukurou.result.model.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ログの種類。 */
@Serializable
public enum class LogKind {
    /** fukurou 自身。 */
    @SerialName("harness")
    HARNESS,

    /** サーバーのコンソールの記録。 */
    @SerialName("server")
    SERVER,

    /** クライアントの latest.log。 */
    @SerialName("client")
    CLIENT,

    /** クラッシュレポート。 */
    @SerialName("crash")
    CRASH,
}
