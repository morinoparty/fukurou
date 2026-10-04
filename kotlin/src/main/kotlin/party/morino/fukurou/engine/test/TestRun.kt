package party.morino.fukurou.engine.test

import party.morino.fukurou.engine.step.TestDeadline
import party.morino.fukurou.result.StepEvent
import party.morino.fukurou.result.model.step.StepPhase
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * 実行中のテスト 1 件の状態（入力を受けたか・置き去りのレーンがあるか・失敗の記録）。
 *
 * ステップ・parallel・スクリーンショットはこれを通して記録先（testId）と期限を知る。JUnit 拡張の ServerLease も
 * 同じものを使う（ServerInstance.beginTest / finishTest）。レーンから同時に触られるので、可変の状態はスレッド安全にする。
 *
 * @property host テストが走るサーバー
 * @property testId result の tests[].id
 * @property session 実行するセッションの index
 * @param timeout テストの期限（ソフトデッドライン）
 */
internal class TestRun(
    val host: StepHost,
    val testId: String,
    val session: Int,
    timeout: Duration,
) {
    /** 期限。すべての待ちはこの残り時間で打ち切る。 */
    val deadline: TestDeadline = TestDeadline(timeout)

    /** StepScope が層を指定しないステップの層（JUnit 拡張は setUp の間 BEFORE_EACH にする）。 */
    @Volatile
    var phase: StepPhase = StepPhase.TEST

    /** 次のステップの仮 id。 */
    private val stepIds = AtomicLong(0)

    /** 次の parallel ブロックの番号（beforeEach・fixture・テストを通して 0 から数える）。 */
    private val blocks = AtomicInteger(0)

    /** 次の repeat ブロックの番号（parallel とは別に、テストを通して 0 から数える）。 */
    private val repeatBlocks = AtomicInteger(0)

    /**
     * クライアントへ入力したプレイヤー。passed で終わらなければ画面が開いたままかもしれないので、次に使う前に起動し直す。
     */
    val touched: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 期限切れの猶予を過ぎてもレーンが操作し続けていた（置き去りにした）プレイヤー。失敗時の撮影でも触らない。 */
    val stranded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * "<player>/<name>" → そのスクリーンショットの持ち主（撮ったステップの仮 id、または eventually / awaitUntil の
     * QuietBlock）。名前の重複を知らせる。
     */
    private val screenshotNames = ConcurrentHashMap<String, Any>()

    /** リセットの失敗（ResetInfo.error）。あればテストは error（phase reset）。 */
    @Volatile
    var resetError: String? = null

    /** 例外 → それを投げたステップ。StatusMapper に失敗したステップを渡すのに使う。 */
    private val errorSteps: MutableMap<Throwable, StepEvent> = Collections.synchronizedMap(IdentityHashMap())

    /** 時間順で最初に失敗したステップ。 */
    @Volatile
    var firstFailedStep: StepEvent? = null
        private set

    /** 次のステップの仮 id を取る。 */
    fun nextStepId(): Long = stepIds.getAndIncrement()

    /** これまでに始めたステップの数（期限切れのメッセージの「step N の前」に使う）。 */
    val stepCount: Long get() = stepIds.get()

    /** 次の parallel ブロックの番号を取る。 */
    fun nextBlock(): Int = blocks.getAndIncrement()

    /** 次の repeat ブロックの番号を取る。 */
    fun nextRepeatBlock(): Int = repeatBlocks.getAndIncrement()

    /**
     * ステップが失敗した。error はそのステップが投げた例外（打ち切りなら null）。
     *
     * step {} の中のステップが失敗すると、同じ例外で外側のステップも失敗する。例外には最初に記録した
     * （いちばん内側の）ステップを結びつけたままにする。コルーチンのスタックトレースの復元は、withTimeout を
     * 抜けるたびに元の例外を写す（写しの cause は元の例外）ので、原因の連なりの全部に同じステップを結びつけ、
     * 既に結びついたステップがあればそれを使う。
     */
    @Synchronized
    fun stepFailed(event: StepEvent, error: Throwable?) {
        if (firstFailedStep == null) firstFailedStep = event
        if (error == null) return
        val step = stepOf(error) ?: event
        causes(error).forEach { errorSteps.putIfAbsent(it, step) }
    }

    /** 例外 error を投げたステップ。原因をたどって探し、見つからなければ null。 */
    fun stepOf(error: Throwable?): StepEvent? = causes(error).firstNotNullOfOrNull { errorSteps[it] }

    /** error とその原因（循環に備えて MAX_CAUSES 段まで）。 */
    private fun causes(error: Throwable?): Sequence<Throwable> =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)

    /** 例外をステップの失敗として記録済みか（parallel でハーネスの不具合とステップの失敗を見分ける）。 */
    fun isStepError(error: Throwable): Boolean = errorSteps.containsKey(error)

    /** 例外を指定のステップに結びつける（parallel の期限切れをレーンの打ち切りに結びつける）。 */
    fun attach(error: Throwable, event: StepEvent) {
        errorSteps[error] = event
    }

    /**
     * スクリーンショットの名前を取る。同じ持ち主（eventually の試行どうし）なら撮り直しとして許す。
     *
     * @param owner 撮ったステップの仮 id（Long）か、記録しない試行のブロック（同一性で比べる）
     * @throws IllegalArgumentException このテストで同じプレイヤーが同じ名前を別の持ち主で使った
     */
    fun claimScreenshot(player: String, name: String, owner: Any) {
        // 同じ名前で上書きすると、先に撮った画像が result.json から指せなくなる
        val earlier = screenshotNames.putIfAbsent("$player/$name", owner) ?: return
        if (earlier == owner) return
        val where = if (earlier is Long) "step $earlier" else "an earlier eventually/awaitUntil block"
        throw IllegalArgumentException("screenshot '$name' of $player was already taken in this test ($where); use a unique name")
    }

    /** 定数。 */
    private companion object {
        /** 原因をたどる段数の上限（循環に備える）。 */
        const val MAX_CAUSES = 8
    }
}
