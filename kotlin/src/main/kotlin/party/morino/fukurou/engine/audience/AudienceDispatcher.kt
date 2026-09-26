package party.morino.fukurou.engine.audience

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import party.morino.fukurou.engine.session.ServerInstance
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.spi.capability.AudienceCommands
import party.morino.fukurou.spi.model.CommandCall
import kotlin.coroutines.EmptyCoroutineContext

/**
 * suspend でない Adventure の操作を、計画 → 送信 → 記録 に橋渡しする（§1.9）。
 *
 * 呼び出し元のスレッドを応答が返るまで止める（各コマンドは RCON の 10 秒で打ち切られる）。
 * StepScope は ThreadLocal なので、IO に移る前に呼び出し元のスレッドで読み、runBlocking のコンテキストに載せ直す。
 */
internal object AudienceDispatcher {
    /**
     * 種類の AudienceCommands で計画して送る。
     *
     * @param method Adventure のメソッド名（非対応のときのメッセージ）
     * @throws UnsupportedCapabilityException 種類が AudienceCommands を持たない
     */
    fun dispatch(server: ServerInstance, method: String, plan: (AudienceCommands) -> List<CommandCall>) {
        val commands = server.capability(AudienceCommands::class) ?: unsupported(server, method)
        execute(server, plan(commands))
    }

    /** 計画済みのコマンドを 1 つずつ送り、1 コマンド 1 ステップで記録する（呼び出し元を止める）。 */
    fun execute(server: ServerInstance, calls: List<CommandCall>) {
        if (calls.isEmpty()) return
        // IO のスレッドでは ThreadLocal が空なので、ここで読んだスコープを引き継ぐ
        val scope = StepScope.currentBlocking()
        runBlocking(Dispatchers.IO + (scope ?: EmptyCoroutineContext)) { server.executeCalls(calls) }
    }

    /** 対応するコマンドが無い操作。Adventure の「何もしない」ではなく投げる（黙って捨てると緑のテストになる）。 */
    fun unsupported(server: ServerInstance, method: String): Nothing = throw UnsupportedCapabilityException(server.type.id, method)
}
