package party.morino.fukurou.engine.paper.command

import net.kyori.adventure.key.Key
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location
import java.math.BigDecimal

/**
 * バニラのコンソールコマンドの文字列（§4.4 の表）。純粋。
 *
 * Minecraft のコマンドの文字列を持つのは engine/paper だけにする（EngineHasNoPaperStringsTest）。
 */
internal object VanillaCommands {
    /** op / deop で既にその状態なら返る応答。失敗ではない。 */
    const val NOTHING_CHANGED: String = "Nothing changed"

    /** kill で消すものが無いときの応答。 */
    const val NO_ENTITY_FOUND: String = "No entity was found"

    /** 次元を指定してテレポートする。tp 自体は実行者の次元を使うので execute in で次元を決める。 */
    fun teleport(player: String, to: Location): CommandCall =
        CommandCall("execute in ${to.world.asString()} run tp $player ${to.toCommandArgs()}", player = player)

    /** 次元を指定しないテレポート（リセットで使う）。 */
    fun plainTeleport(player: String, args: String): CommandCall = CommandCall("tp $player $args", player = player)

    /**
     * 位置を変えずに向きだけを変える。at @s でプレイヤーの位置と次元を実行位置にし、~ ~ ~ でその場へテレポートする。
     * 数値は Location.toCommandArgs と同じく末尾の 0 を落とす（90.0 → "90"）。
     */
    fun rotate(player: String, yaw: Float, pitch: Float): CommandCall {
        require(yaw.isFinite() && pitch.isFinite()) { "yaw and pitch must be finite numbers (got $yaw, $pitch)" }
        return CommandCall("execute as $player at @s run tp @s ~ ~ ~ ${angle(yaw)} ${angle(pitch)}", player = player)
    }

    /** Float は toString 経由で BigDecimal にし（0.1f が 0.10000000149… にならないように）、指数表記にしない。 */
    private fun angle(value: Float): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    /** ゲームモードの変更。 */
    fun gamemode(player: String, mode: GameMode): CommandCall = CommandCall("gamemode ${mode.id} $player", player = player)

    /** op にする。既に op なら "Nothing changed" が返るが失敗ではない。 */
    fun op(player: String): CommandCall = CommandCall("op $player", ignore = listOf(NOTHING_CHANGED), player = player)

    /** op を外す。既に op でなければ "Nothing changed" が返るが失敗ではない。 */
    fun deop(player: String): CommandCall = CommandCall("deop $player", ignore = listOf(NOTHING_CHANGED), player = player)

    /** アイテムを与える。 */
    fun give(player: String, item: Key, count: Int): CommandCall =
        CommandCall("give $player ${item.asString()} $count", player = player)

    /** 次元を指定して直方体を埋める。 */
    fun fill(from: BlockPos, to: BlockPos, block: String, world: Key): CommandCall =
        CommandCall("execute in ${world.asString()} run fill ${from.toCommandArgs()} ${to.toCommandArgs()} $block")

    /** 次元を指定して 1 ブロックを置く。 */
    fun setBlock(at: BlockPos, block: String, world: Key): CommandCall =
        CommandCall("execute in ${world.asString()} run setblock ${at.toCommandArgs()} $block")

    /** 時刻を tick で設定する。 */
    fun time(ticks: Long): CommandCall = CommandCall("time set $ticks")

    /** 天気を晴れにする。 */
    fun weatherClear(): CommandCall = CommandCall("weather clear")

    /** プレイヤーがチャット欄からコマンドを送ったときにサーバーログに出る行。 */
    fun issuedCommand(player: String, commandWithoutSlash: String): Regex =
        Regex("${Regex.escape(player)} issued server command: /${Regex.escape(commandWithoutSlash)}")

    /** サーバーログでこのプレイヤーの参加を示す行。名前の一部が一致する別のプレイヤーと取り違えないよう \b を付ける。 */
    fun joined(player: String): Regex = Regex("\\b${Regex.escape(player)} joined the game")
}
