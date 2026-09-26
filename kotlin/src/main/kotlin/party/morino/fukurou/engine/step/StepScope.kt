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
 */
internal class StepScope(
    val run: TestRun?,
    val phase: StepPhase? = null,
    val fixture: String? = null,
    val block: ParallelBlock? = null,
    val lane: Int? = null,
) : AbstractCoroutineContextElement(Key),
    ThreadContextElement<StepScope?> {
    /** 一部だけを変えた写し。 */
    fun copy(
        phase: StepPhase? = this.phase,
        fixture: String? = this.fixture,
        block: ParallelBlock? = this.block,
        lane: Int? = this.lane,
    ): StepScope = StepScope(run, phase, fixture, block, lane)

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
