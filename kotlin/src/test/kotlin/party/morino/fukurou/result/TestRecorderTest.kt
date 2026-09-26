package party.morino.fukurou.result

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.enums.TestFailurePhase
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.step.StepPhase
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.result.model.test.ScreenshotInfo
import java.time.Instant

class TestRecorderTest {
    private val t0 = Instant.parse("2026-09-26T00:00:00Z")
    private val planned = PlannedTest("A", "a", "a", "a", emptyList(), 0, "junit:A#a", "0")

    private fun step(id: Long, status: StepStatus? = null, screenshot: String? = null) =
        StepEvent(id, StepPhase.TEST, null, "Alice", "screenshot", "shot", t0, status = status, screenshot = screenshot)

    @Test
    @DisplayName("A planned test is a not-run stub with no steps")
    fun stub() {
        val result = TestRecorder(planned).build()
        assertEquals(TestStatus.SKIPPED, result.status)
        assertEquals(TestRecorder.NOT_RUN, result.skipReason)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    @DisplayName("The first failure wins over the final outcome")
    fun firstFailureWins() {
        val test = TestRecorder(planned)
        test.start(0)
        test.setReset(ResetInfo(5, "gamemode failed"))
        test.finish(TestOutcome(TestStatus.FAILED, TestFailurePhase.SCENARIO, "later"))
        val result = test.build()
        assertEquals(TestStatus.ERROR, result.status)
        assertEquals(TestFailurePhase.RESET, result.failure?.phase)
        assertEquals("gamemode failed", result.failure?.message)
    }

    @Test
    @DisplayName("A late pass after a timeout is ignored and its screenshot dropped")
    fun latePassIgnored() {
        val warnings = mutableListOf<String>()
        val test = TestRecorder(planned, warn = { warnings += it })
        test.start(0)
        test.stepStarted(step(1))
        test.stepFinished(step(1, StepStatus.FAILED).copy(error = "the test exceeded its timeout"))
        test.addScreenshot(ScreenshotInfo("Alice", "shot", "p.png", 1, 1), 1)
        // 置き去りにしたレーンが、期限切れの記録の後に passed で終わった
        test.stepFinished(step(1, StepStatus.PASSED, screenshot = "p.png"))
        test.finish(TestOutcome(TestStatus.ERROR, TestFailurePhase.TIMEOUT, "the test exceeded its timeout"))
        test.stepFinished(step(1, StepStatus.PASSED))
        val result = test.build()
        assertEquals(StepStatus.FAILED, result.steps.single().status)
        assertTrue(result.screenshots.isEmpty())
        assertEquals(TestFailurePhase.TIMEOUT, result.failure?.phase)
        // 失敗にステップが示されなければ、最初に失敗したステップを指す
        assertEquals(0, result.failure?.stepIndex)
        assertEquals(2, warnings.size)
    }

    @Test
    @DisplayName("An unfinished step is failed with the stopping reason")
    fun unfinishedStep() {
        val test = TestRecorder(planned)
        test.start(0)
        test.stepStarted(step(1))
        test.finish(TestOutcome(TestStatus.ERROR, TestFailurePhase.TIMEOUT, "the test exceeded its timeout"))
        val step = test.build().steps.single()
        assertEquals(StepStatus.FAILED, step.status)
        assertEquals("the test exceeded its timeout", step.error)
    }

    @Test
    @DisplayName("Skip requires a reason and clears execution traces")
    fun skip() {
        val test = TestRecorder(planned)
        assertThrows<IllegalArgumentException> { test.skip("") }
        test.start(0)
        test.stepStarted(step(1))
        test.skip("server died during b")
        val result = test.build()
        assertEquals(TestStatus.SKIPPED, result.status)
        assertEquals("server died during b", result.skipReason)
        assertTrue(result.steps.isEmpty())
        assertNull(result.session)
    }
}
