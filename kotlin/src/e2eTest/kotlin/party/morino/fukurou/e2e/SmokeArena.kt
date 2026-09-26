package party.morino.fukurou.e2e

import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.player.Player
import party.morino.fukurou.server.ServerSpec

/**
 * fukurou の CI の e2e 用の最小のサーバー（§8.4）。プラグインは入れず、プレイヤーは 2 人。
 *
 * 種類は既定の Paper.fromProperties（CI が -Pfukurou.minecraftVersion を渡す）。
 * 2 人にするのは、同時のスクリーンショット（レーンの並行）と、あるプレイヤーの発言が別のクライアントに届くことを確かめるため。
 */
class SmokeArena : GameServerExtension() {
    /** op のプレイヤー。チャット欄からのコマンドを送る。 */
    val alice: Player by player("Alice", op = true)

    /** op ではないプレイヤー。Alice の発言とサーバーからのメッセージを受け取る。 */
    val bob: Player by player("Bob")

    override fun ServerSpec.configure() {
        label = "smoke"
    }
}
