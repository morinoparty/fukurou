package party.morino.fukurou.engine.step

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.result.model.step.StepPhase
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * ステップの記録先と位置（テスト・層・fixture 名・parallel のブロックとレーン）をコルーチンに載せる。
 *
 * ThreadContextElement でもあるので、同じスレッドの suspend でない呼び出し（Adventure の Audience の操作）も
 * ThreadLocal から読める（§1.8）。
 *
 * @property run 記録先のテスト。null なら記録せず harness.log にだけ残す（サーバーの起動直後の onStarted など）
 * @property phase ステップの層。null ならテストの層（TestRun.phase）
 * @property fixture phase が FIXTURE のときの名前
 * @property block 実行中の parallel ブロック（外なら null）
 * @property lane parallel ブロックの中のレーン番号
 * @property repeat 実行中の repeat ブロックと何回目か（外なら null）
 * @property quiet true ならステップを記録しない（eventually / awaitUntil の試行）。期限の確認と例外の分類はする
 * @property quietBlock quiet のときの、いちばん外側の eventually / awaitUntil（試行の中のスクリーンショットの持ち主）
 * @property implicit スコープの無いところ（JUnit のテスト本体）で repeat / parallel が作ったスコープ。
 *   run は実行中のテストの 1 つ目にすぎないので、サーバーに属さないステップはスコープが無いときと同じく
 *   実行中のテストすべてに記録する（§1.8）
 */
internal class StepScope(
    val run: TestRun?,
    val phase: StepPhase? = null,
    val fixture: String? = null,
    val block: ParallelBlock? = null,
    val lane: Int? = null,
    val repeat: RepeatFrame? = null,
    val quiet: Boolean = false,
    val quietBlock: QuietBlock? = null,
    val implicit: Boolean = false,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<StepScope?> {
    /** 一部だけを変えた写し。 */
    fun copy(
        phase: StepPhase? = this.phase,
        fixture: String? = this.fixture,
        block: ParallelBlock? = this.block,
        lane: Int? = this.lane,
        repeat: RepeatFrame? = this.repeat,
        quiet: Boolean = this.quiet,
        quietBlock: QuietBlock? = this.quietBlock,
    ): StepScope = StepScope(run, phase, fixture, block, lane, repeat, quiet, quietBlock, implicit)

    /** コルーチンがこのスレッドで再開するときに ThreadLocal へ載せ、前の値を返す。 */
    override fun updateThreadContext(context: CoroutineContext): StepScope? {
        val previous = LOCAL.get()
        LOCAL.set(this)
        return previous
    }

    /** 中断したときに前の値へ戻す。 */
    override fun restoreThreadContext(context: CoroutineContext, oldState: StepScope?) {
        LOCAL.set(oldState)
    }

    /** コンテキストのキーと、今のスコープの取得。 */
    companion object Key : CoroutineContext.Key<StepScope> {
        /** suspend でない呼び出しから読むためのスレッドごとの値。 */
        private val LOCAL = ThreadLocal<StepScope?>()

        /** 今のコルーチンのスコープ。コンテキストに無ければ ThreadLocal を見る。 */
        suspend fun current(): StepScope? = currentCoroutineContext()[Key] ?: LOCAL.get()

        /** suspend でない呼び出し元のスレッドのスコープ（Adventure の操作用）。 */
        fun currentBlocking(): StepScope? = LOCAL.get()
    }
}

/**
 * 記録しない試行を繰り返すブロック（eventually / awaitUntil）1 つ。同一性で比べる。
 *
 * 試行の中のスクリーンショットは同じ名前で撮り直してよく（後の試行が上書きする）、ブロック自身のステップに結びつける。
 *
 * @property anchors 記録先のテスト → そのテストでのブロックのステップの仮 id（ステップを記録しなかったテストは無い）
 */
internal class QuietBlock(val anchors: Map<TestRun, Long>) {
    /** run でのブロックのステップの仮 id。無ければ null（スクリーンショットはどのステップにも結びつけない）。 */
    fun anchorFor(run: TestRun): Long? = anchors[run]
}
