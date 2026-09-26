package party.morino.fukurou.engine.paper.capability

import net.kyori.adventure.key.Key
import party.morino.fukurou.engine.paper.command.VanillaCommands
import party.morino.fukurou.spi.capability.CommandEcho
import party.morino.fukurou.spi.capability.PlayerCommands
import party.morino.fukurou.spi.capability.WorldCommands
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location

/** Paper のプレイヤー・ワールドの操作とコマンドのログの形（1 クラスで 3 つの能力）。純粋。 */
internal object PaperPlayerCommands : PlayerCommands, WorldCommands, CommandEcho {
    override fun teleport(player: String, to: Location): List<CommandCall> = listOf(VanillaCommands.teleport(player, to))

    override fun gamemode(player: String, mode: GameMode): List<CommandCall> = listOf(VanillaCommands.gamemode(player, mode))

    override fun op(player: String): List<CommandCall> = listOf(VanillaCommands.op(player))

    override fun deop(player: String): List<CommandCall> = listOf(VanillaCommands.deop(player))

    override fun give(player: String, item: Key, count: Int): List<CommandCall> =
        listOf(VanillaCommands.give(player, item, count))

    override fun fill(from: BlockPos, to: BlockPos, block: String, world: Key): List<CommandCall> =
        listOf(VanillaCommands.fill(from, to, block, world))

    override fun setBlock(at: BlockPos, block: String, world: Key): List<CommandCall> =
        listOf(VanillaCommands.setBlock(at, block, world))

    override fun time(ticks: Long): List<CommandCall> = listOf(VanillaCommands.time(ticks))

    override fun weatherClear(): List<CommandCall> = listOf(VanillaCommands.weatherClear())

    override fun issuedCommand(player: String, commandWithoutSlash: String): Regex =
        VanillaCommands.issuedCommand(player, commandWithoutSlash)
}
