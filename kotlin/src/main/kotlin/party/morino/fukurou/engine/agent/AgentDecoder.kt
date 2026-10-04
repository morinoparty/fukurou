package party.morino.fukurou.engine.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.event.ServerEvent
import party.morino.fukurou.state.BlockSnapshot
import party.morino.fukurou.state.EffectSnapshot
import party.morino.fukurou.state.EntitySnapshot
import party.morino.fukurou.state.InventoryViewSnapshot
import party.morino.fukurou.state.ItemSnapshot
import party.morino.fukurou.state.PlayerSnapshot
import party.morino.fukurou.state.WorldSnapshot
import party.morino.fukurou.world.Location
import java.util.UUID

/**
 * エージェントのスナップショットとイベントの JSON（v3 設計 §1.3）を公開の型に読む。
 *
 * Component は Adventure の GSON 形式、キーは名前空間付きの文字列。形が違えば FukurouException（ハーネスの誤り）。
 */
internal object AgentDecoder {
    /** BlockSnapshot。 */
    fun block(json: JsonElement): BlockSnapshot = decode("a block") {
        val obj = json.obj()
        BlockSnapshot(
            world = obj.key("world"),
            x = obj.int("x"),
            y = obj.int("y"),
            z = obj.int("z"),
            type = obj.key("type"),
            data = obj.string("data"),
        )
    }

    /** WorldSnapshot。 */
    fun world(json: JsonElement): WorldSnapshot = decode("a world") {
        val obj = json.obj()
        WorldSnapshot(
            key = obj.key("key"),
            name = obj.string("name"),
            time = obj.long("time"),
            fullTime = obj.long("fullTime"),
            storm = obj.boolean("storm"),
            thundering = obj.boolean("thundering"),
            difficulty = obj.string("difficulty"),
            players = obj.array("players").map { it.text() },
        )
    }

    /** EntitySnapshot の配列。 */
    fun entities(json: JsonElement): List<EntitySnapshot> = decode("entities") { json.list().map(::entityOf) }

    /** EntitySnapshot。 */
    fun entity(json: JsonElement): EntitySnapshot = decode("an entity") { entityOf(json) }

    /** PlayerSnapshot。 */
    fun player(json: JsonElement): PlayerSnapshot = decode("a player") {
        val obj = json.obj()
        PlayerSnapshot(
            name = obj.string("name"),
            uuid = UUID.fromString(obj.string("uuid")),
            location = locationOf(obj.required("location")),
            gameMode = obj.string("gameMode"),
            health = obj.double("health"),
            maxHealth = obj.double("maxHealth"),
            food = obj.int("food"),
            saturation = obj.double("saturation").toFloat(),
            level = obj.int("level"),
            exp = obj.double("exp").toFloat(),
            flying = obj.boolean("flying"),
            sneaking = obj.boolean("sneaking"),
            sprinting = obj.boolean("sprinting"),
            op = obj.boolean("op"),
            selectedSlot = obj.int("selectedSlot"),
            inventory = obj.array("inventory").map(::itemOf),
            openInventory = obj.optional("openInventory")?.let(::inventoryOf),
            effects = obj.arrayOrEmpty("effects").map { effect ->
                val e = effect.obj()
                EffectSnapshot(e.key("type"), e.int("amplifier"), e.int("duration"))
            },
            tags = obj.arrayOrEmpty("tags").map { it.text() }.toSet(),
        )
    }

    /** Location。 */
    fun location(json: JsonElement): Location = decode("a location") { locationOf(json) }

    /** Item（空のスロットの null は null）。 */
    fun item(json: JsonElement): ItemSnapshot? = decode("an item") { itemOf(json) }

    /** イベントの通知（{"subscription","type","tick","cancelled","fields"}）。 */
    fun event(json: JsonObject): ServerEvent = decode("an event") {
        ServerEvent(
            type = json.string("type"),
            tick = json.optional("tick")?.primitive()?.longOrNull ?: 0L,
            cancelled = json.optional("cancelled")?.primitive()?.booleanOrNull,
            fields = json.optional("fields") as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** Component（null か JSON の null なら null）。 */
    fun component(json: JsonElement?): Component? {
        if (json == null || json is JsonNull) return null
        return decode("a component") { GsonComponentSerializer.gson().deserialize(json.toString()) }
    }

    /** 読み取りの失敗をハーネスの失敗にする。 */
    private inline fun <T> decode(what: String, block: () -> T): T =
        try {
            block()
        } catch (error: FukurouException) {
            throw error
        } catch (error: RuntimeException) {
            throw FukurouException("the fukurou agent sent $what in an unexpected shape: ${error.message ?: error.javaClass.simpleName}", error)
        }

    /** EntitySnapshot の本体。 */
    private fun entityOf(json: JsonElement): EntitySnapshot {
        val obj = json.obj()
        return EntitySnapshot(
            uuid = UUID.fromString(obj.string("uuid")),
            type = obj.key("type"),
            location = locationOf(obj.required("location")),
            name = component(obj["name"]),
            customName = component(obj["customName"]),
            tags = obj.arrayOrEmpty("tags").map { it.text() }.toSet(),
            health = obj.optional("health")?.primitive()?.doubleOrNull,
            dead = obj.optional("dead")?.primitive()?.booleanOrNull ?: false,
        )
    }

    /** Location の本体。 */
    private fun locationOf(json: JsonElement): Location {
        val obj = json.obj()
        val yaw = obj.optional("yaw")?.primitive()?.doubleOrNull?.toFloat()
        val pitch = obj.optional("pitch")?.primitive()?.doubleOrNull?.toFloat()
        // Location は片方だけの向きを作れないので、両方そろったときだけ載せる
        val both = yaw != null && pitch != null
        return Location(
            x = obj.double("x"),
            y = obj.double("y"),
            z = obj.double("z"),
            yaw = if (both) yaw else null,
            pitch = if (both) pitch else null,
            world = obj.key("world"),
        )
    }

    /** Item の本体。 */
    private fun itemOf(json: JsonElement): ItemSnapshot? {
        if (json is JsonNull) return null
        val obj = json.obj()
        val enchantments = (obj.optional("enchantments") as? JsonObject).orEmpty()
            .map { (key, level) -> Key.key(key) to level.primitive().int() }
            .toMap()
        return ItemSnapshot(
            type = obj.key("type"),
            amount = obj.int("amount"),
            name = component(obj["name"]),
            lore = obj.arrayOrEmpty("lore").mapNotNull { component(it) },
            customModelData = obj.optional("customModelData")?.primitive()?.intOrNull,
            enchantments = enchantments,
            damage = obj.optional("damage")?.primitive()?.intOrNull,
            unbreakable = obj.optional("unbreakable")?.primitive()?.booleanOrNull ?: false,
        )
    }

    /** 開いているコンテナの画面。 */
    private fun inventoryOf(json: JsonElement): InventoryViewSnapshot {
        val obj = json.obj()
        return InventoryViewSnapshot(
            type = obj.string("type"),
            title = component(obj["title"]) ?: Component.empty(),
            size = obj.int("size"),
            contents = obj.arrayOrEmpty("contents").map(::itemOf),
        )
    }

    // --- JSON の小道具 ---------------------------------------------------------------

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("expected an object, got $this")

    private fun JsonElement.list(): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("expected an array, got $this")

    private fun JsonElement.primitive(): JsonPrimitive =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: throw IllegalArgumentException("expected a value, got $this")

    private fun JsonElement.text(): String = primitive().also { require(it.isString) { "expected a string, got $it" } }.content

    private fun JsonPrimitive.int(): Int = intOrNull ?: throw IllegalArgumentException("expected an integer, got $this")

    /** 値のあるキー（null は無いものとする）。 */
    private fun JsonObject.optional(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

    private fun JsonObject.required(key: String): JsonElement = optional(key) ?: throw IllegalArgumentException("missing '$key'")

    private fun JsonObject.string(key: String): String = required(key).primitive().contentOrNull ?: throw IllegalArgumentException("missing '$key'")

    private fun JsonObject.int(key: String): Int = required(key).primitive().intOrNull ?: throw IllegalArgumentException("'$key' is not an integer")

    private fun JsonObject.long(key: String): Long = required(key).primitive().longOrNull ?: throw IllegalArgumentException("'$key' is not an integer")

    private fun JsonObject.double(key: String): Double = required(key).primitive().doubleOrNull ?: throw IllegalArgumentException("'$key' is not a number")

    private fun JsonObject.boolean(key: String): Boolean =
        required(key).primitive().booleanOrNull ?: throw IllegalArgumentException("'$key' is not a boolean")

    private fun JsonObject.key(key: String): Key = Key.key(string(key))

    private fun JsonObject.array(key: String): JsonArray = required(key).list()

    private fun JsonObject.arrayOrEmpty(key: String): JsonArray = optional(key)?.list() ?: JsonArray(emptyList())
}
