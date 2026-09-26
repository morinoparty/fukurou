package party.morino.fukurou.engine.step

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.result.RunRecorder
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.run.FukurouInfo
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.step.ParallelInfo
import party.morino.fukurou.result.model.step.StepResult
import party.morino.fukurou.result.model.suite.SelectionInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ParallelRunnerTest {
    /** Minecraft を使わないサーバーの代わり。記録は本物の RunRecorder に流す。 */
    private class FakeHost : StepHost {
        override val resultId: String = "paper-26.3-test"
        val recorder = RunRecorder(resultId, "test", MinecraftInfo("26.3"), FukurouInfo("dev", "5.0.4"), null, SelectionInfo(), writer = null)
        override val observer: RunRecorder get() = recorder
        override val harnessScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var died: ServerUnavailableException? = null
        var deadClient: ClientDiedException? = null

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun checkClientsAlive() {
            deadClient?.let { throw it }
        }

        override fun deadClient(): ClientDiedException? = deadClient

        override fun serverDied(error: ServerUnavailableException) {
            died = error
        }
    }

    private val host = FakeHost()

    /** 同じテストを実行する 2 台目のサーバー。 */
    private val hostB = FakeHost()

    @AfterEach
    fun tearDown() {
        host.harnessScope.cancel()
        hostB.harnessScope.cancel()
    }

    /** テスト t を始める。 */
    private fun begin(timeout: Duration = 1.minutes, on: FakeHost = host): TestRun {
        on.recorder.runPlanned(listOf(PlannedTest("A", "t", "t", "t", emptyList(), 0, "junit:A#t", "0")))
        on.recorder.sessionStarted(0, SessionKind.INITIAL)
        on.recorder.testStarted("t", 0, emptyList())
        return TestRun(on, "t", 0, timeout)
    }

    /** 記録されたステップ。 */
    private fun steps(): List<StepResult> = host.recorder.build().tests.single().steps

    /** 記録される 1 ステップ。 */
    private suspend fun step(on: String, label: String, body: suspend () -> Unit) {
        StepRunner.step(host, on, "press_key", label) { _, _ -> body() }
    }

    @Test
    @DisplayName("Lanes run to the end, are recorded lane 0 first, and the earliest failure is rethrown")
    fun contiguousLanes() {
        val run = begin()
        val error = assertThrows<AssertionError> {
            runBlocking(StepScope(run)) {
                val runner = ParallelRunner()
                runner.lane {
                    step("Alice", "a1") { delay(100.milliseconds) }
                    step("Alice", "a2") {}
                }
                runner.lane { step("Bob", "b1") { throw AssertionError("b1 failed") } }
                runner.run()
            }
        }
        assertEquals("b1 failed", error.message)
        val steps = steps()
        // 兄弟のレーンが失敗しても a2 まで走り、ブロックのステップはレーン 0 から連続して並ぶ
        assertEquals(listOf("a1", "a2", "b1"), steps.map { it.label })
        assertEquals(listOf(ParallelInfo(0, 0), ParallelInfo(0, 0), ParallelInfo(0, 1)), steps.map { it.parallel })
        assertEquals(listOf(StepStatus.PASSED, StepStatus.PASSED, StepStatus.FAILED), steps.map { it.status })
        assertEquals(1, run.nextBlock())
    }

    @Test
    @DisplayName("A server death cancels the sibling lanes and is rethrown")
    fun serverDeath() {
        val run = begin()
        assertThrows<ServerUnavailableException> {
            runBlocking(StepScope(run)) {
                val runner = ParallelRunner()
                runner.lane { step("server", "list") { throw ServerUnavailableException("gone") } }
                runner.lane { step("Bob", "wait") { delay(30.seconds) } }
                runner.run()
            }
        }
        assertNotNull(host.died)
        val cancelled = steps().single { it.label == "wait" }
        assertEquals(StepStatus.FAILED, cancelled.status)
        assertEquals("cancelled: gone", cancelled.error)
    }

    @Test
    @DisplayName("At the deadline a stuck lane is abandoned and its player marked stranded")
    fun deadlineStrands() {
        val run = begin(timeout = 300.milliseconds)
        val error = assertThrows<HarnessTimeoutException> {
            runBlocking(StepScope(run)) {
                val runner = ParallelRunner(grace = 100.milliseconds)
                // 取り消しを無視して外部プロセスの中で止まっているレーン
                runner.lane { step("Alice", "stuck") { withContext(NonCancellable) { delay(2.seconds) } } }
                runner.run()
            }
        }
        assertTrue(error.message!!.startsWith("the test exceeded its timeout of 0s during parallel block 0"), error.message)
        assertEquals(setOf("Alice"), run.stranded)
        val stuck = steps().single()
        assertEquals(StepStatus.FAILED, stuck.status)
        assertNull(stuck.durationMs)
        assertEquals("stuck", run.stepOf(error)?.label)
    }

    @Test
    @DisplayName("A failed input after a client died is recorded as the client's death")
    fun deadClient() {
        val run = begin()
        assertThrows<ClientDiedException> {
            runBlocking(StepScope(run)) {
                step("Alice", "t") {
                    host.deadClient = ClientDiedException("Alice", "Alice: client exited with code 1")
                    throw IllegalStateException("xdotool failed")
                }
            }
        }
        assertEquals("Alice: client exited with code 1", steps().single().error)
    }

    @Test
    @DisplayName("A step cancelled by the caller's own timeout is skipped, not failed")
    fun callerTimeout() {
        val run = begin()
        runBlocking(StepScope(run)) {
            // 「来ないこと」を確かめる使い方。テストは成功のまま進む
            val result = withTimeoutOrNull(100.milliseconds) { step("Alice", "never") { delay(30.seconds) } }
            assertNull(result)
        }
        val step = steps().single()
        assertEquals(StepStatus.SKIPPED, step.status)
        assertNull(run.firstFailedStep)
    }

    @Test
    @DisplayName("Log waits in a scope that records nothing ignore the finished test's deadline")
    fun deadlineOutsideTest() {
        val run = begin()
        ActiveTests.begin(run, run)
        try {
            runBlocking {
                assertSame(run.deadline, StepRunner.deadlineOf(host))
                // tearDown / onStarted は StepScope(run = null) で走る
                withContext(StepScope(run = null)) { assertNull(StepRunner.deadlineOf(host)) }
            }
        } finally {
            ActiveTests.finish(run)
        }
    }

    @Test
    @DisplayName("Lane steps on a second server use that test's own block and are flushed in order")
    fun twoServers() {
        val runA = begin()
        val runB = begin(on = hostB)
        // B のテストでは既に 1 つのブロックを使った（番号 0）
        runB.nextBlock()
        ActiveTests.begin(runA, "owner")
        ActiveTests.begin(runB, "owner")
        try {
            // JUnit のテスト本体から（StepScope 無しで）呼ぶ
            runBlocking {
                val runner = ParallelRunner()
                runner.lane { StepRunner.step(host, "Alice", "press_key", "a") { _, _ -> } }
                runner.lane { StepRunner.step(hostB, "Bob", "press_key", "b") { _, _ -> } }
                runner.run()
                StepRunner.step(hostB, "Bob", "press_key", "after") { _, _ -> }
            }
        } finally {
            ActiveTests.finish(runA)
            ActiveTests.finish(runB)
        }
        val stepsB = hostB.recorder.build().tests.single().steps
        // ブロックの終わりで B にも流され、後のステップより前に並ぶ。番号は B のテストのもの
        assertEquals(listOf("b", "after"), stepsB.map { it.label })
        assertEquals(ParallelInfo(1, 1), stepsB.first().parallel)
        assertEquals(ParallelInfo(0, 0), steps().single().parallel)
    }

    @Test
    @DisplayName("Wait labels use Python's %g seconds")
    fun waitLabel() {
        assertEquals("1.5", StepRunner.seconds(1500.milliseconds))
        assertEquals("2", StepRunner.seconds(2.seconds))
        assertEquals("0.25", StepRunner.seconds(250.milliseconds))
    }
}
