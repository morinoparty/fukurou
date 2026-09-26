package party.morino.fukurou.engine.step

import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.result.StepEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 実行中の parallel ブロック 1 つの状態（run/parallel.py の Lane.current と StepExecutor.cancel の移植）。
 *
 * レーンごとに実行中のステップを覚え、期限を過ぎても戻らないレーンのステップを timeout で記録できるようにする。
 * レーンを打ち切った理由（期限切れ・サーバーの死亡）も持ち、打ち切られたステップはその理由で failed になる。
 *
 * 1 つのテストを 2 台のサーバーで実行しているとき（§1.8）、レーンは別のサーバーのテスト（TestRun）にも記録する。
 * そのテストには自分のブロック番号を割り当て、ブロックの終わりにそれぞれへ blockFinished を送れるよう覚えておく。
 *
 * @property index 持ち主のテストでのブロック番号
 * @property owner ブロックを始めたテスト（テストの外なら null）
 */
internal class ParallelBlock(val index: Int, val owner: TestRun? = null) {
    /** 同じプレイヤーへ 2 つのレーンから入力させない。 */
    val guard: LaneGuard = LaneGuard(index)

    /** レーンを打ち切った理由。打ち切られたステップの error になる。最初の理由を残す。 */
    @Volatile
    var cancelReason: String? = null
        private set

    /** レーン → 実行中のステップと、その記録先のテスト。 */
    private val running = ConcurrentHashMap<Int, Pair<TestRun, StepEvent>>()

    /** 持ち主以外のテスト → そのテストでのこのブロックの番号。 */
    private val otherRuns = ConcurrentHashMap<TestRun, Int>()

    /**
     * run でのこのブロックの番号。持ち主以外のテストなら、最初に記録したときにそのテストの番号を取る
     * （持ち主の番号を使うと、そのテストの他のブロックと番号が重なり、並びも崩れる）。
     */
    fun indexFor(run: TestRun): Int =
        if (owner == null || run === owner) index else otherRuns.computeIfAbsent(run) { it.nextBlock() }

    /** 持ち主以外で、このブロックのステップを記録したテストとその番号。 */
    fun otherRuns(): Map<TestRun, Int> = otherRuns.toMap()

    /** 打ち切りの理由を決める（既に決まっていれば変えない）。 */
    @Synchronized
    fun cancel(reason: String) {
        if (cancelReason == null) cancelReason = reason
    }

    /** レーンのステップが始まった。run はそのステップの記録先。 */
    fun stepStarted(lane: Int, run: TestRun, event: StepEvent) {
        running[lane] = run to event
    }

    /** レーンのステップが終わった（成功・失敗とも）。 */
    fun stepEnded(lane: Int, event: StepEvent) {
        running.computeIfPresent(lane) { _, current -> current.takeUnless { it.second === event } }
    }

    /** レーンで実行中のステップとその記録先。無ければ null。 */
    fun running(lane: Int): Pair<TestRun, StepEvent>? = running[lane]
}
