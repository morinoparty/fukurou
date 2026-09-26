package party.morino.fukurou.engine.test

import kotlinx.coroutines.CoroutineScope
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.result.RunObserver

/**
 * ステップを記録するテストが属するサーバー（engine.session.ServerInstance が実装する）。
 *
 * ステップの実行と parallel（engine.step）がサーバーの実装に直接依存しないよう、必要な操作だけをここに集める。
 * 単体テストではこれを偽物に差し替えて、Minecraft なしでステップの分類とレーンの規則を確かめる。
 */
internal interface StepHost {
    /** result id（レーンのコルーチン名やハーネスログに使う）。 */
    val resultId: String

    /** 記録係。 */
    val observer: RunObserver

    /**
     * レーンを起動するスコープ（SupervisorJob + Dispatchers.IO）。
     *
     * 呼び出し元のスコープではなくここで起動するのは、期限を過ぎても止まらないレーンを置き去りにできるようにするため
     * （構造化された並行性では子を置き去りにできない）。
     */
    val harnessScope: CoroutineScope

    /** harness.log への情報。 */
    fun log(message: String)

    /** harness.log への警告。 */
    fun warn(message: String)

    /**
     * 参加中のクライアントがすべて生きているかを確かめる（scenario_runner.py:114 check_players_alive）。
     *
     * @throws ClientDiedException 死んだクライアントがある
     */
    fun checkClientsAlive()

    /** 参加中のクライアントのうち死んでいるものの例外。全員生きていれば null（step_executor.py:124 _dead_client）。 */
    fun deadClient(): ClientDiedException?

    /** ステップがサーバーの死亡（RCON 不通・プロセスの終了）を見た。以後のテストは走らない。 */
    fun serverDied(error: ServerUnavailableException)
}
