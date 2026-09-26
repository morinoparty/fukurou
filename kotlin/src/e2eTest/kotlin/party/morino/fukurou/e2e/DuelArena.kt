package party.morino.fukurou.e2e

import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.player.Player
import party.morino.fukurou.server.ServerSpec

/**
 * 複数サーバーの e2e（MultiServerTest）の 2 台目。プラグインは入れず、プレイヤーは 1 人。
 *
 * LobbyArena と同時に動かすので、同じくサーバーのヒープを 1G に下げる（見積もり 4210 MB、2 台で 8420 MB）。
 */
class DuelArena : GameServerExtension() {
    /** op のプレイヤー。デュエル側のコマンドとメッセージを受け取る。 */
    val dave: Player by player("Dave", op = true)

    override fun ServerSpec.configure() {
        // result id と出力ディレクトリを LobbyArena と分ける
        label = "duel"
        // 素の Paper に 1 人だけなので 1G で足りる。2 台同時でも予算に収めるため
        serverHeap = "1G"
    }
}
