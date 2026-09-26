package party.morino.fukurou.result.model.kind

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** プラグインの役割。 */
@Serializable
public enum class PluginRole {
    /** テスト対象。 */
    @SerialName("under-test")
    UNDER_TEST,

    /** 依存。 */
    @SerialName("dependency")
    DEPENDENCY,
}
