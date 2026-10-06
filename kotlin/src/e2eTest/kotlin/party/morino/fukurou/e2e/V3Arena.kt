package party.morino.fukurou.e2e

import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.player.Player
import party.morino.fukurou.plugin.PluginSource
import party.morino.fukurou.server.ServerSpec

/**
 * v3 のエージェント経由の機能（状態の取得・イベント・execute）を本物のクライアントで通す e2e（V3Test）のサーバー。
 *
 * プレイヤーは op の Alice 1 人。コンパニオンプラグイン（kotlin/e2e-companion）を入れ、e2e:online などのタスクを使う。
 * jar のパスは e2eTest タスクが -Dfukurou.plugin.companion で渡す。
 * 隔離は既定の Isolation.Reset（各テストの前にアリーナを空気で埋め直し、インベントリを空にして、
 * Alice を (0.5, -60, -8.5) へ戻し、サバイバルにする）。
 */
class V3Arena : GameServerExtension() {
    /** op のプレイヤー。歩く・掘る・話す。 */
    val alice: Player by player("Alice", op = true)

    override fun ServerSpec.configure() {
        // result id と出力ディレクトリを他の e2e と分ける
        label = "v3"
        // 素の Paper（とコンパニオン）に 1 人だけなので 1G で足りる
        serverHeap = "1G"
        plugins { underTest(PluginSource.systemProperty("companion")) }
    }
}
