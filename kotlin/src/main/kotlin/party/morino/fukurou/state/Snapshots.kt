package party.morino.fukurou.state

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import party.morino.fukurou.world.Location
import java.util.UUID

/**
 * アイテム 1 スタック（エージェントの Item の JSON、v3 設計 §1.3）。
 *
 * @property type アイテムのキー（minecraft:diamond）
 * @property amount 個数
 * @property name 表示名（付いていなければ null）
 * @property lore 説明文の行
 * @property customModelData CustomModelData（無ければ null）
 * @property enchantments エンチャントのキー → レベル
 * @property damage 耐久値の減り（耐久の無いアイテムは null）
 * @property unbreakable 壊れないか
 */
public data class ItemSnapshot(
    val type: Key,
    val amount: Int,
    val name: Component?,
    val lore: List<Component>,
    val customModelData: Int?,
    val enchantments: Map<Key, Int>,
    val damage: Int?,
    val unbreakable: Boolean,
)

/**
 * 開いているコンテナの画面（チェストなど）。
 *
 * @property type Bukkit の InventoryType の名前（CHEST、HOPPER など）
 * @property title 画面のタイトル
 * @property size 上側のインベントリのスロット数
 * @property contents 上側のインベントリの中身（空のスロットは null）
 */
public data class InventoryViewSnapshot(
    val type: String,
    val title: Component,
    val size: Int,
    val contents: List<ItemSnapshot?>,
)

/**
 * 掛かっているポーション効果。
 *
 * @property type 効果のキー（minecraft:speed）
 * @property amplifier 強さ（0 始まり）
 * @property duration 残りの tick 数
 */
public data class EffectSnapshot(val type: Key, val amplifier: Int, val duration: Int)

/**
 * サーバーから見たプレイヤーの状態（エージェントの PlayerSnapshot）。
 *
 * @property inventory getContents() の 41 スロット。0〜35 が本体（0〜8 がホットバー）、36〜39 が防具（足→頭）、40 がオフハンド
 * @property openInventory 開いているコンテナの画面。自分のインベントリ（または何も開いていない）なら null
 */
public data class PlayerSnapshot(
    val name: String,
    val uuid: UUID,
    val location: Location,
    val gameMode: String,
    val health: Double,
    val maxHealth: Double,
    val food: Int,
    val saturation: Float,
    val level: Int,
    val exp: Float,
    val flying: Boolean,
    val sneaking: Boolean,
    val sprinting: Boolean,
    val op: Boolean,
    val selectedSlot: Int,
    val inventory: List<ItemSnapshot?>,
    val openInventory: InventoryViewSnapshot?,
    val effects: List<EffectSnapshot>,
    val tags: Set<String>,
) {
    /** 手に持っているアイテム（ホットバーの選択中のスロット）。 */
    public val mainHand: ItemSnapshot? get() = inventory.getOrNull(selectedSlot)

    /** オフハンドのアイテム。 */
    public val offHand: ItemSnapshot? get() = inventory.getOrNull(OFF_HAND_SLOT)

    /** type のアイテムの合計個数（41 スロットすべて）。 */
    public fun count(type: Key): Int = inventory.filterNotNull().filter { it.type == type }.sumOf { it.amount }

    private companion object {
        /** オフハンドのスロット。 */
        private const val OFF_HAND_SLOT = 40
    }
}

/**
 * 1 ブロック。
 *
 * @property world 次元のキー
 * @property type ブロックのキー（minecraft:oak_stairs）
 * @property data ブロック状態の文字列（minecraft:oak_stairs[facing=east,…]）。setBlock / fill にそのまま渡せる
 */
public data class BlockSnapshot(val world: Key, val x: Int, val y: Int, val z: Int, val type: Key, val data: String)

/**
 * エンティティ（プレイヤーを含む）。
 *
 * @property type エンティティのキー（minecraft:zombie、プレイヤーは minecraft:player）
 * @property name 表示される名前
 * @property customName 付けられた名前（無ければ null）
 * @property tags スコアボードのタグ
 * @property health 体力（生き物でなければ null）
 */
public data class EntitySnapshot(
    val uuid: UUID,
    val type: Key,
    val location: Location,
    val name: Component?,
    val customName: Component?,
    val tags: Set<String>,
    val health: Double?,
    val dead: Boolean,
)

/**
 * エンティティの絞り込み。すべて省略すると全ワールドの全エンティティ（最大 [limit] 件）。
 *
 * @property world この次元だけ
 * @property type このエンティティの種類だけ（minecraft:zombie）
 * @property near この位置から [radius] ブロック以内だけ（world が無ければ near.world を使う）
 * @property tag このスコアボードのタグを持つものだけ
 * @property limit 最大件数（1〜4096）
 */
public data class EntityQuery(
    val world: Key? = null,
    val type: Key? = null,
    val near: Location? = null,
    val radius: Double = 16.0,
    val tag: String? = null,
    val limit: Int = 256,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in 1..$MAX_LIMIT, got $limit" }
        require(radius > 0) { "radius must be positive, got $radius" }
    }

    private companion object {
        /** エージェントが返す最大件数。 */
        private const val MAX_LIMIT = 4096
    }
}

/**
 * ワールドの状態。
 *
 * @property key 次元のキー
 * @property name Bukkit のワールド名（world、world_nether など）
 * @property time 1 日の中の時刻（0〜23999）
 * @property fullTime 通算の時刻
 * @property difficulty 難易度（peaceful など）
 * @property players このワールドにいるプレイヤー名
 */
public data class WorldSnapshot(
    val key: Key,
    val name: String,
    val time: Long,
    val fullTime: Long,
    val storm: Boolean,
    val thundering: Boolean,
    val difficulty: String,
    val players: List<String>,
)
