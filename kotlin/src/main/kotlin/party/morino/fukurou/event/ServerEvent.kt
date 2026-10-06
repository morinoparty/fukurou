package party.morino.fukurou.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * サーバーで起きたイベント 1 件（エージェントが直列化したもの、v3 設計 §1.4）。
 *
 * @property type イベントのクラスの完全修飾名（org.bukkit.event.block.BlockBreakEvent）
 * @property tick 起きたときの Bukkit.getCurrentTick()
 * @property cancelled Cancellable なら MONITOR の時点で取り消されていたか。Cancellable でなければ null
 * @property fields getX / isX のプロパティ（getPlayer → "player"）と、Component を返す Adventure 流のアクセサ
 *   （message() → "message"、値は Component の JSON）。Player はプレイヤー名の文字列
 */
public data class ServerEvent(
    val type: String,
    val tick: Long,
    val cancelled: Boolean?,
    val fields: JsonObject,
) {
    /** クラスの単純名（BlockBreakEvent）。 */
    public val simpleName: String get() = type.substringAfterLast('.').substringAfterLast('$')

    /** プロパティの値。無ければ null。 */
    public operator fun get(field: String): JsonElement? = fields[field]

    /** 文字列のプロパティ（数値や真偽値は文字列にする）。無いか null なら null。 */
    public fun string(field: String): String? = primitive(field)?.contentOrNull

    /** 整数のプロパティ。 */
    public fun long(field: String): Long? = primitive(field)?.longOrNull

    /** 数値のプロパティ。 */
    public fun double(field: String): Double? = primitive(field)?.doubleOrNull

    /** 真偽値のプロパティ。 */
    public fun boolean(field: String): Boolean? = primitive(field)?.booleanOrNull

    /** "player" のプロパティ（PlayerEvent のプレイヤー名）。 */
    public val player: String? get() = string("player")

    /** プリミティブの値。JSON の null・オブジェクト・配列なら null。 */
    private fun primitive(field: String): JsonPrimitive? = (fields[field] as? JsonPrimitive)?.takeUnless { it is JsonNull }
}
