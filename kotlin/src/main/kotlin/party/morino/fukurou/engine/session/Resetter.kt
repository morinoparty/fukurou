package party.morino.fukurou.engine.session

import kotlinx.coroutines.delay
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.spi.capability.ResetPlanner

/**
 * ワールドと参加プレイヤーをテストの前の状態に戻す（run/session.py:180-210 reset の移植）。
 *
 * コマンドはステップとして記録しない（所要時間と失敗は ResetInfo に入る）。最初に失敗したコマンドで止める。
 * 終わりに mark したログのここまでの行を照合の対象から外す（logRanges には残る）。リセットのコマンドは
 * サーバーログと OP のクライアントのチャットに写るので、外さないとテストの照合がハーネス自身の出力に一致してしまう。
 */
internal object Resetter {
    /**
     * 応答にこれがあれば、プロセスは生きていてもサーバーに居ない（キック・切断）。次のテストの前に起動し直す。
     *
     * バニラの応答の文字列だが、ResetPlanner の SPI に「プレイヤーが居ない」の判定が無いのでここに置く（isolation.py:40 PLAYER_MISSING）。
     */
    private const val PLAYER_MISSING = "No player was found"

    /** dirty の理由: サーバーに居なかった。 */
    private const val DIRTY_MISSING_REASON = "the client was not connected to the server"

    /**
     * リセットする。
     *
     * @return 所要時間とエラー（"<command>: <response>"）
     * @throws party.morino.fukurou.error.ServerUnavailableException サーバーが応答しない
     */
    suspend fun reset(server: ServerInstance, reset: Isolation.Reset): ResetInfo {
        val planner = server.capability(ResetPlanner::class)
            ?: throw SetupException("${server.type.id} servers cannot reset between tests (no ResetPlanner capability)")
        val started = System.nanoTime()
        val participants = server.joinedSessions
        var error: String? = null
        for (call in planner.plan(participants.map { it.profile }, reset)) {
            val response = server.send(call.command).text
            val player = call.player
            if (player != null && response != null && PLAYER_MISSING in response) {
                // 判定（ignore）より前に覚える。参加していても切断されたクライアントは起動し直す
                server.dirty[player] = DIRTY_MISSING_REASON
            }
            if (server.platform.responseCheck.isError(call, response)) {
                error = "${call.command}: ${response.orEmpty().trim()}"
                server.warn("reset failed: $error")
                break
            }
        }
        if (error == null) {
            if (reset.normalizeView) participants.forEach { it.normalizeView() }
            // クライアントがブロックの更新を受け取るまで待つ
            delay(reset.settle)
        }
        server.skipMarked()
        return ResetInfo(durationMs = (System.nanoTime() - started) / NANOS_PER_MILLI, error = error)
    }

    /** ナノ秒 → ミリ秒。 */
    private const val NANOS_PER_MILLI = 1_000_000L
}
