package party.morino.fukurou.engine.step

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    @AfterEach
    fun tearDown() {
        host.harnessScope.cancel()
    }

    /** テスト t を始める。 */
    private fun begin(timeout: Duration = 1.minutes): TestRun {
        host.recorder.runPlanned(listOf(PlannedTest("A", "t", "t", "t", emptyList(), 0, "junit:A#t", "0")))
        host.recorder.sessionStarted(0, SessionKind.INITIAL)
        host.recorder.testStarted("t", 0, emptyList())
        return TestRun(host, "t", 0, timeout)
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
    @DisplayName("Wait labels use Python's %g seconds")
    fun waitLabel() {
        assertEquals("1.5", StepRunner.seconds(1500.milliseconds))
        assertEquals("2", StepRunner.seconds(2.seconds))
        assertEquals("0.25", StepRunner.seconds(250.milliseconds))
    }
}
