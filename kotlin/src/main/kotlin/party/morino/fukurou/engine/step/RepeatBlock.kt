package party.morino.fukurou.engine.step

import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.result.model.step.RepeatInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * 実行中の repeat ブロック 1 つ。中のステップに付ける RepeatInfo の番号を決める。
 *
 * 1 つのテストを 2 台のサーバーで実行しているとき（§1.8）、ステップは別のサーバーのテストにも記録される。
 * そのテストには最初に記録したときに自分の番号を割り当てる（ParallelBlock.indexFor と同じ考え方）。
 *
 * @property index 持ち主のテストでのブロック番号
 * @property owner ブロックを始めたテスト（テストの外なら null）
 * @property times 繰り返しの回数
 */
internal class RepeatBlock(val index: Int, val owner: TestRun?, val times: Int) {
    /** 持ち主以外のテスト → そのテストでのこのブロックの番号。 */
    private val otherRuns = ConcurrentHashMap<TestRun, Int>()

    /** run でのこのブロックの番号。持ち主以外のテストなら、最初に記録したときにそのテストの番号を取る。 */
    fun indexFor(run: TestRun): Int =
        if (owner == null || run === owner) index else otherRuns.computeIfAbsent(run) { it.nextRepeatBlock() }
}

/**
 * repeat ブロックの何回目か。
 *
 * @property block ブロック
 * @property iteration 1 始まりの繰り返し番号
 */
internal class RepeatFrame(val block: RepeatBlock, val iteration: Int) {
    /** run に記録するステップの RepeatInfo。 */
    fun infoFor(run: TestRun): RepeatInfo = RepeatInfo(block.indexFor(run), iteration, block.times)
}
