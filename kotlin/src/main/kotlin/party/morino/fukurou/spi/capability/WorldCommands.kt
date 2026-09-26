package party.morino.fukurou.spi.capability

import net.kyori.adventure.key.Key
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.BlockPos

/** ワールドに対する操作の計画（純粋）。 */
@FukurouSpi
public interface WorldCommands : Capability {
    /** 直方体を埋める。 */
    public fun fill(from: BlockPos, to: BlockPos, block: String, world: Key): List<CommandCall>

    /** 1 ブロックを置く。 */
    public fun setBlock(at: BlockPos, block: String, world: Key): List<CommandCall>

    /** 時刻を設定する。 */
    public fun time(ticks: Long): List<CommandCall>

    /** 天気を晴れにする。 */
    public fun weatherClear(): List<CommandCall>
}
