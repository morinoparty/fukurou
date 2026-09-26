package party.morino.fukurou.e2e

import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.player.Player
import party.morino.fukurou.server.ServerSpec

/**
 * 複数サーバーの e2e（MultiServerTest）の 1 台目。プラグインは入れず、プレイヤーは 1 人。
 *
 * DuelArena と同時に動かすので、2 台とそのクライアントが 16 GB の runner のメモリ予算（MemoryBudget）に
 * 余裕を持って収まるよう、サーバーのヒープを既定の 2G から下げる（見積もり 1024 + 750 + 1536 + 900 = 4210 MB）。
 */
class LobbyArena : GameServerExtension() {
    /** op のプレイヤー。ロビー側のコマンドとメッセージを受け取る。 */
    val carol: Player by player("Carol", op = true)

    override fun ServerSpec.configure() {
        // result id と出力ディレクトリを DuelArena と分ける
        label = "lobby"
        // 素の Paper に 1 人だけなので 1G で足りる。2 台同時でも予算に収めるため
        serverHeap = "1G"
    }
}
