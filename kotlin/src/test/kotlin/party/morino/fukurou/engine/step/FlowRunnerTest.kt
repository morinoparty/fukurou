package party.morino.fukurou.engine.step

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.awaitUntil
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.eventually
import party.morino.fukurou.log.LogAssertionError
import party.morino.fukurou.parallel
import party.morino.fukurou.pause
import party.morino.fukurou.repeat
import party.morino.fukurou.result.RunRecorder
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.run.FukurouInfo
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.step.ParallelInfo
import party.morino.fukurou.result.model.step.RepeatInfo
import party.morino.fukurou.result.model.step.StepResult
import party.morino.fukurou.result.model.test.ScreenshotInfo
import party.morino.fukurou.result.model.suite.SelectionInfo
import party.morino.fukurou.step
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FlowRunnerTest {
    /** Minecraft を使わないサーバーの代わり。記録は本物の RunRecorder に流す。 */
    private class FakeHost(override val resultId: String = "paper-26.3-test") : StepHost {
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
    private val hostB = FakeHost("paper-26.3-b")

    @AfterEach
    fun tearDown() {
        host.harnessScope.cancel()
        hostB.harnessScope.cancel()
    }

    /** テスト t を on で始める。 */
    private fun begin(timeout: Duration = 1.minutes, on: FakeHost = host): TestRun {
        on.recorder.runPlanned(listOf(PlannedTest("A", "t", "t", "t", emptyList(), 0, "junit:A#t", "0")))
        on.recorder.sessionStarted(0, SessionKind.INITIAL)
        on.recorder.testStarted("t", 0, emptyList())
        return TestRun(on, "t", 0, timeout)
    }

    /** 記録されたステップ。 */
    private fun steps(on: FakeHost = host): List<StepResult> = on.recorder.build().tests.single().steps

    /** サーバーへの 1 ステップ。 */
    private suspend fun <T> command(label: String, body: suspend () -> T): T =
        StepRunner.step(host, StepRunner.ON_SERVER, "command", label) { _, _ -> body() }

    /** run のスコープで block を走らせる。 */
    private fun <T> inTest(run: TestRun, block: suspend () -> T): T = runBlocking(StepScope(run)) { block() }

    /** PlayerSession.screenshot と同じ手順で名前を取り、撮ったことにする（width で何回目かを見分ける）。 */
    private suspend fun fakeScreenshot(player: String, name: String, width: Int): String =
        StepRunner.step(host, player, "screenshot", name, screenshotOf = { it }) { run, stepId ->
            checkNotNull(run)
            val owner = StepRunner.claimScreenshot(run, player, name, stepId)
            val path = "tests/t/screenshots/$player/$name.png"
            run.host.observer.screenshotTaken(run.testId, ScreenshotInfo(player, name, path, width, 1), owner)
            path
        }

    @Test
    @DisplayName("screenshot inside eventually may be retaken under the same name and attaches to the eventually step")
    fun screenshotInsideEventually() {
        val run = begin()
        var attempts = 0
        inTest(run) {
            command("before") {}
            eventually(5.seconds, 1.milliseconds) {
                attempts++
                fakeScreenshot("Alice", "door", attempts)
                if (attempts < 3) throw AssertionError("not yet")
            }
        }
        val test = host.recorder.build().tests.single()
        assertEquals(listOf("before", "5s"), test.steps.map { it.label })
        val shot = test.screenshots.single()
        // 後の試行が上書きする
        assertEquals(3, shot.width)
        assertEquals(1, shot.stepIndex)
    }

    @Test
    @DisplayName("screenshot names stay unique across recorded steps and separate eventually blocks")
    fun screenshotNamesStayUnique() {
        val run = begin()
        inTest(run) {
            fakeScreenshot("Alice", "a", 1)
            assertThrows<IllegalArgumentException> { fakeScreenshot("Alice", "a", 2) }
            // 記録したステップと同じ名前は eventually の中でも使えない（AssertionError ではないので再試行もしない）
            assertThrows<IllegalArgumentException> { eventually(1.seconds, 1.milliseconds) { fakeScreenshot("Alice", "a", 3) } }
            eventually(1.seconds, 1.milliseconds) { fakeScreenshot("Alice", "b", 4) }
            assertThrows<IllegalArgumentException> { eventually(1.seconds, 1.milliseconds) { fakeScreenshot("Alice", "b", 5) } }
            // 入れ子の eventually は外側のブロックの試行の一部
            var outer = 0
            eventually(5.seconds, 1.milliseconds) {
                outer++
                eventually(1.seconds, 1.milliseconds) { fakeScreenshot("Alice", "c", outer) }
                if (outer < 2) throw AssertionError("again")
            }
            // 別のプレイヤーなら同じ名前でもよい
            fakeScreenshot("Bob", "a", 6)
        }
        val shots = host.recorder.build().tests.single().screenshots
        assertEquals(listOf("Alice/a", "Alice/b", "Alice/c", "Bob/a"), shots.map { "${it.player}/${it.name}" })
        assertEquals(2, shots.single { it.name == "c" }.width)
    }

    @Test
    @DisplayName("step records the block as one step before the steps inside it")
    fun stepNesting() {
        val run = begin()
        val value = inTest(run) {
            step("give the stamp") {
                command("stamp give Alice") {}
                command("stamp list") { 42 }
            }
        }
        assertEquals(42, value)
        val steps = steps()
        assertEquals(listOf("give the stamp", "stamp give Alice", "stamp list"), steps.map { it.label })
        assertEquals(listOf("step", "command", "command"), steps.map { it.action })
        assertEquals(listOf(null, "server", "server"), steps.map { it.on })
        assertTrue(steps.all { it.status == StepStatus.PASSED })
    }

    @Test
    @DisplayName("A failure inside step fails both steps and stays attached to the inner one")
    fun stepFailure() {
        val run = begin()
        val error = assertThrows<AssertionError> {
            inTest(run) { step("outer") { command("inner") { throw AssertionError("boom") } } }
        }
        assertEquals(listOf(StepStatus.FAILED, StepStatus.FAILED), steps().map { it.status })
        assertEquals("inner", run.stepOf(error)?.label)
        assertEquals("inner", run.firstFailedStep?.label)
    }

    @Test
    @DisplayName("repeat attaches 1-based RepeatInfo to every step and numbers blocks per test")
    fun repeatInfo() {
        val run = begin()
        val indices = mutableListOf<Int>()
        inTest(run) {
            repeat(3) { index ->
                indices += index
                command("c$index") {}
            }
            command("after") {}
            repeat(1) { command("again") {} }
        }
        assertEquals(listOf(0, 1, 2), indices)
        assertEquals(
            listOf(RepeatInfo(0, 1, 3), RepeatInfo(0, 2, 3), RepeatInfo(0, 3, 3), null, RepeatInfo(1, 1, 1)),
            steps().map { it.repeat?.single() },
        )
    }

    @Test
    @DisplayName("repeat rejects nesting and counts outside 0..1000; 0 does nothing")
    fun repeatRules() {
        val run = begin()
        assertThrows<IllegalStateException> { inTest(run) { repeat(2) { repeat(2) {} } } }
        assertThrows<IllegalArgumentException> { inTest(run) { repeat(-1) {} } }
        assertThrows<IllegalArgumentException> { inTest(run) { repeat(1001) {} } }
        // 0 回は kotlin.repeat と同じく何もせず、ブロック番号も取らない
        var called = false
        inTest(run) { repeat(0) { called = true } }
        assertFalse(called)
        inTest(run) { repeat(1) { command("x") { } } }
        // 番号 0 は入れ子にした外側の repeat が取った。repeat(0) は番号を取らないので次は 1
        assertEquals(RepeatInfo(1, 1, 1), steps().single().repeat?.single())
    }

    @Test
    @DisplayName("repeat works inside parallel lanes and around parallel blocks")
    fun repeatWithParallel() {
        val run = begin()
        inTest(run) {
            parallel {
                lane { repeat(2) { command("lane0-$it") {} } }
                lane { command("lane1") {} }
            }
            repeat(2) { iteration ->
                parallel { lane { command("inside-$iteration") {} } }
            }
        }
        val steps = steps()
        assertEquals(listOf("lane0-0", "lane0-1", "lane1", "inside-0", "inside-1"), steps.map { it.label })
        assertEquals(
            listOf(RepeatInfo(0, 1, 2), RepeatInfo(0, 2, 2), null, RepeatInfo(1, 1, 2), RepeatInfo(1, 2, 2)),
            steps.map { it.repeat?.single() },
        )
        assertEquals(listOf(ParallelInfo(0, 0), ParallelInfo(0, 0), ParallelInfo(0, 1), ParallelInfo(1, 0), ParallelInfo(2, 0)), steps.map { it.parallel })
    }

    @Test
    @DisplayName("eventually retries assertion failures and records only itself")
    fun eventuallyRetries() {
        val run = begin()
        var attempts = 0
        val value = inTest(run) {
            eventually(timeout = 5.seconds, interval = 10.milliseconds) {
                command("stamp list") {}
                // assertNoLog のような即時のステップも記録しない
                StepRunner.instant(host, StepRunner.ON_SERVER, "assert_no_log", "x") {}
                pause(1.milliseconds)
                attempts++
                if (attempts < 3) throw LogAssertionError("not yet")
                "done"
            }
        }
        assertEquals("done", value)
        assertEquals(3, attempts)
        val step = steps().single()
        assertEquals("eventually", step.action)
        assertEquals("5s", step.label)
        assertNull(step.on)
        assertEquals(StepStatus.PASSED, step.status)
        assertNull(run.firstFailedStep)
    }

    @Test
    @DisplayName("eventually rethrows the last assertion failure with the attempt count when it times out")
    fun eventuallyTimesOut() {
        val run = begin()
        var attempts = 0
        val error = assertThrows<AssertionError> {
            inTest(run) {
                eventually(timeout = 200.milliseconds, interval = 20.milliseconds) {
                    attempts++
                    command("probe") { throw AssertionError("attempt $attempts") }
                }
            }
        }
        assertTrue(attempts > 1, "attempts: $attempts")
        assertEquals("attempt $attempts", error.message)
        // コルーチンのスタックトレースの復元で写されていれば、元の例外は cause にある
        val summary = generateSequence<Throwable>(error) { it.cause }.flatMap { it.suppressed.asSequence() }.first().message!!
        assertTrue(summary.startsWith("eventually gave up after $attempts attempts in 0.2s"), summary)
        val step = steps().single()
        assertEquals(StepStatus.FAILED, step.status)
        assertEquals("attempt $attempts", step.error)
        assertEquals(step.label, run.stepOf(error)?.label)
    }

    @Test
    @DisplayName("eventually does not retry a server death, but the quiet step still marks the server dead")
    fun eventuallyFatal() {
        val run = begin()
        var attempts = 0
        assertThrows<ServerUnavailableException> {
            inTest(run) {
                eventually(timeout = 5.seconds, interval = 10.milliseconds) {
                    attempts++
                    command("list") { throw ServerUnavailableException("gone") }
                }
            }
        }
        assertEquals(1, attempts)
        assertNotNull(host.died)
        assertEquals(listOf("eventually" to StepStatus.FAILED), steps().map { it.action to it.status })
    }

    @Test
    @DisplayName("eventually does not retry ordinary exceptions")
    fun eventuallyOtherException() {
        val run = begin()
        var attempts = 0
        assertThrows<IllegalStateException> {
            inTest(run) {
                eventually(timeout = 5.seconds, interval = 10.milliseconds) {
                    attempts++
                    error("bug")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    @DisplayName("eventually turns a failed input into the client's death when a client died")
    fun eventuallyDeadClient() {
        val run = begin()
        assertThrows<ClientDiedException> {
            inTest(run) {
                eventually(timeout = 5.seconds, interval = 10.milliseconds) {
                    command("probe") {
                        host.deadClient = ClientDiedException("Alice", "Alice: client exited with code 1")
                        throw AssertionError("xdotool failed")
                    }
                }
            }
        }
        assertEquals("Alice: client exited with code 1", steps().single().error)
    }

    @Test
    @DisplayName("The test deadline ends eventually with HarnessTimeoutException")
    fun eventuallyDeadline() {
        val run = begin(timeout = 300.milliseconds)
        assertThrows<HarnessTimeoutException> {
            inTest(run) {
                eventually(timeout = 10.seconds, interval = 20.milliseconds) { command("probe") { throw AssertionError("not yet") } }
            }
        }
        val step = steps().single()
        assertEquals("eventually", step.action)
        assertEquals(StepStatus.FAILED, step.status)
        assertTrue(step.error!!.startsWith("the test exceeded its timeout"), step.error)
    }

    @Test
    @DisplayName("An attempt that blocks is cut off at the eventually timeout")
    fun eventuallyHungAttempt() {
        val run = begin()
        val error = assertThrows<AssertionError> {
            inTest(run) { eventually(timeout = 150.milliseconds, interval = 10.milliseconds) { delay(10.seconds) } }
        }
        assertTrue(error.message!!.contains("did not finish"), error.message)
    }

    @Test
    @DisplayName("awaitUntil records one step labelled with the description")
    fun awaitUntilPasses() {
        val run = begin()
        var checks = 0
        inTest(run) {
            awaitUntil("the stamp is given", timeout = 5.seconds, interval = 10.milliseconds) {
                command("stamp count") {}
                ++checks >= 3
            }
        }
        val step = steps().single()
        assertEquals("await_until" to "the stamp is given", step.action to step.label)
        assertEquals(StepStatus.PASSED, step.status)
        assertEquals(3, checks)
    }

    @Test
    @DisplayName("awaitUntil fails with a timeout message naming the description")
    fun awaitUntilTimesOut() {
        val run = begin()
        val error = assertThrows<AssertionError> {
            inTest(run) { awaitUntil("the door opens", timeout = 100.milliseconds, interval = 10.milliseconds) { false } }
        }
        assertEquals("timed out after 0.1s waiting until the door opens", error.message)
        assertEquals(StepStatus.FAILED, steps().single().status)
    }

    @Test
    @DisplayName("Without a scope, flow steps are recorded into every test running on both servers")
    fun twoServers() {
        val runA = begin()
        val runB = begin(on = hostB)
        // B のテストでは既に repeat ブロックを 1 つ使った（番号 0）
        runB.nextRepeatBlock()
        ActiveTests.begin(runA, "owner")
        ActiveTests.begin(runB, "owner")
        try {
            // JUnit のテスト本体から（StepScope 無しで）呼ぶ
            runBlocking {
                step("both") {
                    StepRunner.step(hostB, "Bob", "chat", "hi") { _, _ -> }
                }
                eventually(timeout = 5.seconds, interval = 10.milliseconds) {
                    StepRunner.step(hostB, "Bob", "chat", "quiet") { _, _ -> }
                }
                repeat(1) { StepRunner.step(hostB, "Bob", "chat", "again") { _, _ -> } }
            }
        } finally {
            ActiveTests.finish(runA)
            ActiveTests.finish(runB)
        }
        assertEquals(listOf("step", "eventually"), steps().map { it.action })
        assertEquals(listOf("both", "hi", "5s", "again"), steps(hostB).map { it.label })
        // B のステップには B のテストでのブロック番号を付ける
        assertEquals(RepeatInfo(1, 1, 1), steps(hostB).last().repeat?.single())
    }

    @Test
    @DisplayName("Hostless steps inside repeat and parallel lanes without a StepScope reach every running test")
    fun twoServersInsideBlocks() {
        val runA = begin()
        val runB = begin(on = hostB)
        ActiveTests.begin(runA, "owner")
        ActiveTests.begin(runB, "owner")
        try {
            // JUnit のテスト本体から（StepScope 無しで）呼ぶ。repeat / parallel が作るスコープは A のテストのもの
            runBlocking {
                repeat(2) { i ->
                    step("walk") { StepRunner.step(hostB, "Bob", "chat", "w$i") { _, _ -> } }
                    pause(1.milliseconds)
                    eventually(timeout = 5.seconds, interval = 10.milliseconds) { }
                    awaitUntil("ready", timeout = 5.seconds, interval = 10.milliseconds) { true }
                }
                parallel {
                    lane {
                        pause(1.milliseconds)
                        step("lane") { }
                    }
                    lane { StepRunner.step(hostB, "Bob", "chat", "lane1") { _, _ -> } }
                }
            }
        } finally {
            ActiveTests.finish(runA)
            ActiveTests.finish(runB)
        }
        val iteration = listOf("step", "wait", "eventually", "await_until")
        assertEquals(iteration + iteration + listOf("wait", "step"), steps().map { it.action })
        val iterationB = listOf("step", "chat", "wait", "eventually", "await_until")
        assertEquals(iterationB + iterationB + listOf("wait", "step", "chat"), steps(hostB).map { it.action })
        // B のステップにも B のテストでの repeat / parallel のブロック番号を付ける
        assertEquals(List(5) { RepeatInfo(0, 1, 2) } + List(5) { RepeatInfo(0, 2, 2) }, steps(hostB).take(10).map { it.repeat?.single() })
        assertEquals(listOf(ParallelInfo(0, 0), ParallelInfo(0, 0), ParallelInfo(0, 1)), steps(hostB).drop(10).map { it.parallel })
        assertTrue(steps(hostB).all { it.status == StepStatus.PASSED })
    }

    @Test
    @DisplayName("A second server's death inside flow steps marks only that server dead")
    fun twoServersDeath() {
        val runA = begin()
        val runB = begin(on = hostB)
        ActiveTests.begin(runA, "owner")
        ActiveTests.begin(runB, "owner")
        try {
            runBlocking {
                assertThrows<ServerUnavailableException> {
                    step("x") { StepRunner.step(hostB, StepRunner.ON_SERVER, "command", "list") { _, _ -> throw ServerUnavailableException("B gone") } }
                }
                assertThrows<ServerUnavailableException> {
                    eventually(timeout = 5.seconds, interval = 10.milliseconds) {
                        StepRunner.step(hostB, StepRunner.ON_SERVER, "command", "list") { _, _ -> throw ServerUnavailableException("B gone") }
                    }
                }
            }
        } finally {
            ActiveTests.finish(runA)
            ActiveTests.finish(runB)
        }
        assertNotNull(hostB.died)
        assertNull(host.died)
        assertEquals(listOf("step", "eventually"), steps().map { it.action })
        assertTrue(steps().all { it.status == StepStatus.FAILED })
        assertEquals(listOf("x", "list", "5s"), steps(hostB).map { it.label })
        assertTrue(steps(hostB).all { it.status == StepStatus.FAILED })
    }

    @Test
    @DisplayName("Flow helpers outside a test run without recording")
    fun outsideTest() {
        val value = runBlocking {
            step("free") { eventually(timeout = 1.seconds) { 1 } }
        }
        assertEquals(1, value)
    }
}
