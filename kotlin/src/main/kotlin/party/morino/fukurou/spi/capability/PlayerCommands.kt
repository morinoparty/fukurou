package party.morino.fukurou.spi.capability

import net.kyori.adventure.key.Key
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location

/** プレイヤーに対するサーバー側の操作の計画（純粋）。 */
@FukurouSpi
public interface PlayerCommands : Capability {
    /** テレポート。 */
    public fun teleport(player: String, to: Location): List<CommandCall>

    /** ゲームモードの変更。 */
    public fun gamemode(player: String, mode: GameMode): List<CommandCall>

    /** op にする（"Nothing changed" は無視する）。 */
    public fun op(player: String): List<CommandCall>

    /** op を外す（"Nothing changed" は無視する）。 */
    public fun deop(player: String): List<CommandCall>

    /** アイテムを与える。 */
    public fun give(player: String, item: Key, count: Int): List<CommandCall>
}
