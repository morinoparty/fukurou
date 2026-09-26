package party.morino.fukurou.engine.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import party.morino.fukurou.engine.test.TestRun
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 失敗したテストの各プレイヤーの画面を failure.png として残す（run/suite_run.py:562-584）。
 *
 * 診断のための撮影なので、撮れなくても（ウィンドウが無い等）本来の失敗を隠さず警告に留める。
 */
internal object FailureCapture {
    /** 失敗時の撮影ではウィンドウを長く待たない（suite_run.py:52 FAILURE_WINDOW_TIMEOUT）。 */
    val WINDOW_TIMEOUT: Duration = 10.seconds

    /**
     * 参加中で生きている、置き去りにされていないプレイヤーを同時に撮る。
     *
     * @param stepId 失敗したステップの仮 id（screenshots[].stepIndex）
     */
    suspend fun capture(server: ServerInstance, run: TestRun, stepId: Long?) {
        val targets = server.joinedSessions.filter { player ->
            when {
                !player.running || player.deathReason() != null -> false
                player.name in run.stranded -> {
                    // 置き去りにしたレーンがまだこの画面へ入力しているかもしれない。同じウィンドウを 2 か所から操作しない
                    server.warn("${player.name}: skipping the failure screenshot; the abandoned lane may still be using the client")
                    false
                }
                else -> true
            }
        }
        coroutineScope {
            targets.map { player ->
                async {
                    try {
                        player.captureFailure(run, stepId, WINDOW_TIMEOUT)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        server.warn("${player.name}: could not capture a failure screenshot: ${error.message}")
                    }
                }
            }.awaitAll()
        }
    }
}
