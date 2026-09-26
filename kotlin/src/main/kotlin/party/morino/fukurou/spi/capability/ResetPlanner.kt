package party.morino.fukurou.spi.capability

import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.model.CommandCall

/** テストの前のリセットの計画（純粋）。 */
@FukurouSpi
public interface ResetPlanner : Capability {
    /** isolation.py:70 reset_commands の移植（参加者は全員、駐車なし）。順序に意味がある。 */
    public fun plan(participants: List<PlayerProfile>, reset: Isolation.Reset): List<CommandCall>
}
