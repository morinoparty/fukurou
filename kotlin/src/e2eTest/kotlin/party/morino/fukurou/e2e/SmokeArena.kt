package party.morino.fukurou.e2e

import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.player.Player
import party.morino.fukurou.server.ServerSpec

/**
 * fukurou の CI の e2e 用の最小のサーバー（§8.4）。プラグインは入れず、プレイヤーは 1 人。
 *
 * 種類は既定の Paper.fromProperties（CI が -Pfukurou.minecraftVersion を渡す）。
 */
class SmokeArena : GameServerExtension() {
    /** 参加するプレイヤー。 */
    val alice: Player by player("Alice", op = true)

    override fun ServerSpec.configure() {
        label = "smoke"
    }
}
