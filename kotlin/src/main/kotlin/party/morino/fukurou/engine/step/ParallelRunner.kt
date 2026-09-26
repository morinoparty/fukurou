package party.morino.fukurou.engine.step

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.fukurou.ParallelScope
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.result.StepEvent
import party.morino.fukurou.result.model.enums.StepStatus
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * parallel { lane {…}; lane {…} } を実行する（run/parallel.py:87-200 の移植、§4.12）。
 *
 * レーンはブロックを抜けたときに同時に走り、全レーンの終了を待つ。1 つのレーンが失敗しても他のレーンは最後まで走る。
 * サーバーが死んだときは他のレーンを打ち切り、テストの期限を過ぎたら全レーンを打ち切って猶予（30 秒）だけ待つ。
 * 猶予の後も動いているレーンは置き去りにし、実行中のステップを timeout で記録して、そのプレイヤーを stranded にする。
 *
 * @param grace 打ち切ってからレーンの合流を待つ時間。xdotool（15 秒）や RCON（10 + 10 秒）より長く取る
 */
internal class ParallelRunner(private val grace: Duration = GRACE) : ParallelScope {
    /** 宣言されたレーン（宣言順 = レーン番号）。 */
    private val lanes = mutableListOf<suspend () -> Unit>()

    /** レーンを 1 つ宣言する。 */
    override fun lane(body: suspend () -> Unit) {
        lanes += body
    }

    /**
     * 宣言したレーンを同時に走らせ、全レーンの終了（または期限切れの猶予）を待つ。
     *
     * 例外は サーバーの死亡 → 期限切れ → ハーネスの不具合 → 時間順で最初のステップの失敗 の順に 1 つを投げる（parallel.py:185）。
     */
    suspend fun run() {
        val outer = StepScope.current()
        // 入れ子とレーンの数はレーンを起動する前に検査する
        LaneGuard.checkNotNested(outer?.block?.index)
        LaneGuard.checkLaneCount(lanes.size)
        if (lanes.isEmpty()) return
        // スコープが無ければ実行中のテスト（JUnit のテスト本体から呼ばれた場合）
        val run = if (outer != null) outer.run else ActiveTests.all().firstOrNull()
        val block = ParallelBlock(run?.nextBlock() ?: 0)
        val host = run?.host
        // レーンは呼び出し元ではなくサーバーのスコープで起動する（置き去りにできるように）
        val harness = host?.harnessScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 停止したサーバーのスコープではレーンが起動せず、何もせずに終わってしまう
        check(harness.isActive) { "${host?.resultId ?: "the server"} was stopped; parallel lanes cannot start" }
        val base = outer ?: StepScope(run)
        // レーンの中の例外（時刻つき）。レーンの外へは run() の最後に 1 つだけ投げる
        val failures = ConcurrentLinkedQueue<Pair<Long, Throwable>>()
        val jobs = ArrayList<Job>(lanes.size)
        lanes.forEachIndexed { lane, body ->
            val name = CoroutineName("fukurou-${host?.resultId ?: "standalone"}-lane-${block.index}-$lane")
            jobs += harness.launch(name + base.copy(block = block, lane = lane), start = CoroutineStart.LAZY) {
                try {
                    body()
                } catch (cancelled: CancellationException) {
                    // レーン自身が打ち切られたなら記録は StepRunner が済ませている。レーンが生きたままの取り消し
                    // （利用者の withTimeout など）はレーンの失敗として呼び出し元へ返す
                    if (isActive) failures += System.nanoTime() to cancelled else throw cancelled
                } catch (error: Throwable) {
                    failures += System.nanoTime() to error
                    // サーバーが死んだら他のレーンの待ちは無駄なので打ち切る（parallel.py:117）
                    if (error is ServerUnavailableException) cancelOthers(jobs, lane, block, "cancelled: ${error.message}")
                }
            }
        }
        host?.log("parallel block ${block.index}: starting ${lanes.size} lanes")
        var deadlineError: HarnessTimeoutException? = null
        try {
            jobs.forEach { it.start() }
            val remaining = run?.deadline?.remaining()
            val finished = if (remaining == null) {
                jobs.joinAll()
                true
            } else {
                withTimeoutOrNull(remaining) { jobs.joinAll() } != null
            }
            if (!finished) deadlineError = stopLanes(run!!, block, jobs)
        } catch (cancelled: CancellationException) {
            // 呼び出し元が取り消された（JUnit の割り込みなど）。レーンを止めてから取り消しを伝える
            block.cancel("cancelled: ${cancelled.message ?: "interrupted"}")
            jobs.forEach { it.cancel(CancellationException(block.cancelReason)) }
            withContext(NonCancellable) { withTimeoutOrNull(grace) { jobs.joinAll() } }
            throw cancelled
        } finally {
            // レーン 0 から順に steps へ並べる（contract.md:161）。置き去りにしたレーンの後の記録は無視される
            if (run != null) run.host.observer.blockFinished(run.testId, block.index)
        }
        rethrow(run, failures.sortedBy { it.first }.map { it.second }, deadlineError)
    }

    /**
     * 期限切れ: レーンを打ち切り、猶予の後も動いているレーンを置き去りにする（parallel.py:153-178 _stop_lanes）。
     *
     * @return テストを timeout にする例外
     */
    private suspend fun stopLanes(run: TestRun, block: ParallelBlock, jobs: List<Job>): HarnessTimeoutException {
        val message = "the test exceeded its timeout of ${run.deadline.timeout.inWholeSeconds}s"
        run.host.warn("parallel block ${block.index}: $message; stopping the lanes")
        block.cancel(message)
        jobs.forEach { it.cancel(CancellationException(message)) }
        // 猶予は全レーンで共有する（レーンごとに待つと置き去りにするレーンの数だけ長くなる）
        withTimeoutOrNull(grace) { jobs.joinAll() }
        val error = HarnessTimeoutException("$message during parallel block ${block.index}")
        var first: StepEvent? = null
        jobs.forEachIndexed { lane, job ->
            if (job.isCompleted) return@forEachIndexed
            // 外部プロセス（xdotool・RCON）の中で止まっているレーン。ステップの記録はここで済ませ、レーンは置き去りにする
            run.host.warn("parallel block ${block.index} lane $lane is still running after ${grace.inWholeSeconds}s; leaving it behind")
            val step = block.running(lane) ?: return@forEachIndexed
            val failed = step.copy(
                status = StepStatus.FAILED,
                finishedAt = Instant.now(),
                // 終わっていないので所要時間は分からない
                durationMs = null,
                error = "$message during step ${step.provisionalId}",
            )
            run.host.observer.stepFinished(run.testId, failed)
            run.stepFailed(failed, null)
            if (first == null) first = failed
            // そのレーンがまだ操作しているかもしれないプレイヤーは、失敗時の撮影で触らず次のテストの前に起動し直す
            step.on?.takeIf { it != StepRunner.ON_SERVER }?.let { player ->
                run.stranded += player
                run.host.warn("$player: the client may still be driven by the abandoned lane; it will be relaunched")
            }
        }
        // テストの失敗を、打ち切ったステップ（無ければ最初に失敗したステップ）に結びつける
        (first ?: run.firstFailedStep)?.let { run.attach(error, it) }
        return error
    }

    /** 他のレーンを理由付きで打ち切る。 */
    private fun cancelOthers(jobs: List<Job>, self: Int, block: ParallelBlock, reason: String) {
        block.cancel(reason)
        jobs.forEachIndexed { lane, job -> if (lane != self) job.cancel(CancellationException(block.cancelReason)) }
    }

    /** レーンの例外を サーバーの死亡 → 期限切れ → ハーネスの不具合 → 最初のステップの失敗 の順に投げ直す。 */
    private fun rethrow(run: TestRun?, errors: List<Throwable>, deadlineError: HarnessTimeoutException?) {
        errors.firstOrNull { it is ServerUnavailableException }?.let { throw it }
        deadlineError?.let { throw it }
        // ステップとして記録されなかった例外は、ハーネスかテストのコードの不具合
        errors.firstOrNull { run != null && !run.isStepError(it) }?.let { throw it }
        errors.firstOrNull()?.let { throw it }
    }

    /** 定数。 */
    companion object {
        /** 打ち切ってからレーンの合流を待つ時間（parallel.py:29 GRACE_SECONDS）。 */
        val GRACE: Duration = 30.seconds
    }
}
