package party.morino.fukurou.result

import java.util.SortedMap

/**
 * parallel ブロックのステップをレーンごとに溜め、ブロックの終了時にレーン 0 から順に並べて渡す（§6.2、contract.md:161）。
 *
 * レーンは同時に動くので、届いた順に steps へ入れると別々のレーンのステップが交互に並んでしまう。
 * 契約では 1 ブロックのステップは steps の中で隣り合い、レーン 0 が先。最終的な添字は TestRecorder が付ける。
 * スレッドの安全は呼び出し側（TestRecorder のロック）が守る。
 */
internal class BlockRecorder {
    /** ブロック → レーン → 開始順のステップ。開いているブロックだけを持つ。 */
    private val blocks: SortedMap<Int, SortedMap<Int, MutableList<StepEvent>>> = sortedMapOf()

    /** 書き出し済みのブロック。遅れて始まったステップを見分けるために使う。 */
    private val flushed = mutableSetOf<Int>()

    /** 溜めているステップの仮 id → そのステップのブロック。 */
    private val blockOf = mutableMapOf<Long, Int>()

    /** そのブロックを既に書き出したか。 */
    fun isFlushed(block: Int): Boolean = block in flushed

    /** その仮 id のステップを溜めているか。 */
    fun contains(provisionalId: Long): Boolean = provisionalId in blockOf

    /** 溜めているステップの今の内容。書き出し済みか知らないステップなら null。 */
    fun find(provisionalId: Long): StepEvent? {
        val block = blockOf[provisionalId] ?: return null
        return blocks[block]?.values?.firstNotNullOfOrNull { steps -> steps.firstOrNull { it.provisionalId == provisionalId } }
    }

    /**
     * parallel のステップの開始を溜める。
     *
     * @throws IllegalArgumentException parallel の外のステップ、または書き出し済みのブロックのステップ
     */
    fun start(event: StepEvent) {
        val parallel = requireNotNull(event.parallel) { "step ${event.provisionalId} is not inside a parallel block" }
        require(parallel.block !in flushed) { "parallel block ${parallel.block} was already finished" }
        // 同じレーンのステップは開始順に並べる（1 つのレーンの中は順番に実行される）
        blocks.getOrPut(parallel.block) { sortedMapOf() }.getOrPut(parallel.lane) { mutableListOf() }.add(event)
        blockOf[event.provisionalId] = parallel.block
    }

    /**
     * 溜めているステップを新しい内容（stepFinished の出来事）に置き換える。
     *
     * @return 溜めていたなら true。書き出し済みか知らないステップなら false
     */
    fun update(event: StepEvent): Boolean {
        val block = blockOf[event.provisionalId] ?: return false
        val lanes = blocks[block] ?: return false
        for (steps in lanes.values) {
            val position = steps.indexOfFirst { it.provisionalId == event.provisionalId }
            if (position >= 0) {
                // 開始時刻は stepStarted のものを正とする（終了の出来事が別の値を持っていても重なりを保つ）
                steps[position] = event.copy(startedAt = steps[position].startedAt)
                return true
            }
        }
        return false
    }

    /**
     * ブロックを閉じ、そのステップをレーン 0 から順に返す。
     *
     * @return 並べ終えたステップ。溜めていなければ空
     */
    fun flush(block: Int): List<StepEvent> {
        flushed += block
        val lanes = blocks.remove(block) ?: return emptyList()
        // レーン番号の順に連結すれば「レーン 0 が先、1 ブロックは連続」になる
        return lanes.values.flatten().also { steps -> steps.forEach { blockOf.remove(it.provisionalId) } }
    }

    /** 開いているブロックをすべて（ブロック番号の順に）閉じる。blockFinished が来なかったときの後始末。 */
    fun flushAll(): List<StepEvent> = blocks.keys.toList().flatMap { flush(it) }

    /** 開いているブロックのステップを、閉じたときと同じ順で返す（閉じない）。途中の result.json の書き出し用。 */
    fun snapshot(): List<StepEvent> = blocks.values.flatMap { lanes -> lanes.values.flatten() }

    /** すべてを捨てる（テストを skipped にしたとき）。 */
    fun clear() {
        blocks.clear()
        blockOf.clear()
    }
}
