package party.morino.fukurou.engine.step

import party.morino.fukurou.result.StepEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 実行中の parallel ブロック 1 つの状態（run/parallel.py の Lane.current と StepExecutor.cancel の移植）。
 *
 * レーンごとに実行中のステップを覚え、期限を過ぎても戻らないレーンのステップを timeout で記録できるようにする。
 * レーンを打ち切った理由（期限切れ・サーバーの死亡）も持ち、打ち切られたステップはその理由で failed になる。
 *
 * @property index このテストでのブロック番号
 */
internal class ParallelBlock(val index: Int) {
    /** 同じプレイヤーへ 2 つのレーンから入力させない。 */
    val guard: LaneGuard = LaneGuard(index)

    /** レーンを打ち切った理由。打ち切られたステップの error になる。最初の理由を残す。 */
    @Volatile
    var cancelReason: String? = null
        private set

    /** レーン → 実行中のステップ。 */
    private val running = ConcurrentHashMap<Int, StepEvent>()

    /** 打ち切りの理由を決める（既に決まっていれば変えない）。 */
    @Synchronized
    fun cancel(reason: String) {
        if (cancelReason == null) cancelReason = reason
    }

    /** レーンのステップが始まった。 */
    fun stepStarted(lane: Int, event: StepEvent) {
        running[lane] = event
    }

    /** レーンのステップが終わった（成功・失敗とも）。 */
    fun stepEnded(lane: Int, event: StepEvent) {
        running.remove(lane, event)
    }

    /** レーンで実行中のステップ。無ければ null。 */
    fun running(lane: Int): StepEvent? = running[lane]
}
