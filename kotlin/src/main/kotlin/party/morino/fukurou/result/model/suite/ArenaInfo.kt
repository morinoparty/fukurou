package party.morino.fukurou.result.model.suite

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * suite.arena の値。契約では ArenaInfo オブジェクトか false（アリーナのリセットを無効にした）。
 *
 * JSON の型が変わる（オブジェクト / false）ので、sealed と専用のシリアライザで表す。
 */
@Serializable(with = ArenaInfoSerializer::class)
public sealed interface ArenaInfo {
    /**
     * リセット時に空気で埋める領域。
     *
     * @property size x, z 方向の幅
     * @property height 地面からの高さ
     */
    @Serializable
    public data class Area(val size: Int, val height: Int) : ArenaInfo

    /** アリーナのリセットを無効にした（JSON では false）。 */
    public data object Disabled : ArenaInfo
}

/** ArenaInfo を {size, height} または false として読み書きする（JSON 専用）。 */
internal object ArenaInfoSerializer : KSerializer<ArenaInfo> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("party.morino.fukurou.ArenaInfo", JsonElement.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: ArenaInfo) {
        // result.json は JSON でしか書かないので、JSON 以外の形式は受け付けない
        val json = encoder as? JsonEncoder ?: throw SerializationException("ArenaInfo can only be written as JSON")
        when (value) {
            is ArenaInfo.Area -> json.encodeSerializableValue(ArenaInfo.Area.serializer(), value)
            ArenaInfo.Disabled -> json.encodeJsonElement(JsonPrimitive(false))
        }
    }

    override fun deserialize(decoder: Decoder): ArenaInfo {
        val json = decoder as? JsonDecoder ?: throw SerializationException("ArenaInfo can only be read from JSON")
        val element = json.decodeJsonElement()
        // true は契約に無い値なので、false だけを Disabled として受け付ける
        if (element is JsonPrimitive) {
            if (element.booleanOrNull == false) return ArenaInfo.Disabled
            throw SerializationException("arena must be an object or false, got $element")
        }
        return json.json.decodeFromJsonElement(ArenaInfo.Area.serializer(), element)
    }
}
