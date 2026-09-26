package party.morino.fukurou.result

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.enums.TestFailurePhase
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.step.ParallelInfo
import party.morino.fukurou.result.model.step.StepPhase
import party.morino.fukurou.result.model.test.ScreenshotInfo
import java.time.Instant

class BlockRecorderTest {
    private val t0 = Instant.parse("2026-09-26T00:00:00Z")

    /** テスト用のステップの出来事。 */
    private fun step(id: Long, lane: Int?, block: Int = 0, at: Long = id) =
        StepEvent(
            provisionalId = id,
            phase = StepPhase.TEST,
            fixture = null,
            on = "server",
            action = "command",
            label = "step $id",
            startedAt = t0.plusMillis(at),
            parallel = lane?.let { ParallelInfo(block, it) },
        )

    @Test
    @DisplayName("Interleaved lanes are flushed contiguously with lane 0 first")
    fun laneOrder() {
        val recorder = BlockRecorder()
        // レーン 1 が先に始まり、レーン 0 と交互に届く
        listOf(step(1, 1), step(2, 0), step(3, 1), step(4, 0)).forEach(recorder::start)
        assertEquals(listOf(2L, 4L, 1L, 3L), recorder.snapshot().map { it.provisionalId })
        assertEquals(listOf(2L, 4L, 1L, 3L), recorder.flush(0).map { it.provisionalId })
        assertEquals(emptyList<StepEvent>(), recorder.snapshot())
    }

    @Test
    @DisplayName("Failure and screenshot step indices are remapped after the block")
    fun remap() {
        val test = TestRecorder(PlannedTest("A", "a", "a", "a", emptyList(), 0, "junit:A#a", "0"))
        test.start(0)
        test.stepStarted(step(10, null))
        test.stepFinished(step(10, null).copy(status = StepStatus.PASSED, finishedAt = t0))
        // ブロック 0: レーン 1 → レーン 0 の順に届き、レーン 1 が失敗する
        test.stepStarted(step(11, 1))
        test.stepStarted(step(12, 0))
        test.stepFinished(step(11, 1).copy(status = StepStatus.FAILED, finishedAt = t0, error = "boom"))
        test.addScreenshot(ScreenshotInfo("Alice", "shot", "tests/a/screenshots/Alice/shot.png", 1, 1), 12)
        test.stepFinished(step(12, 0).copy(status = StepStatus.PASSED, finishedAt = t0))
        test.blockFinished(0)
        test.finish(TestOutcome(TestStatus.FAILED, TestFailurePhase.SCENARIO, "boom", 11))
        val result = test.build()
        // レーン 0 のステップ 12 が 1、レーン 1 のステップ 11 が 2
        assertEquals(listOf("step 10", "step 12", "step 11"), result.steps.map { it.label })
        assertEquals(listOf(0, 1, 2), result.steps.map { it.index })
        assertEquals(2, result.failure?.stepIndex)
        assertEquals(1, result.screenshots.single().stepIndex)
    }

    @Test
    @DisplayName("A late pass of a buffered lane step keeps the timeout failure")
    fun latePassInBlock() {
        val test = TestRecorder(PlannedTest("A", "a", "a", "a", emptyList(), 0, "junit:A#a", "0"))
        test.start(0)
        test.stepStarted(step(1, 0))
        // 期限切れでエンジンが failed と記録した後、猶予の間にレーンが passed で返った
        test.stepFinished(step(1, 0).copy(status = StepStatus.FAILED, error = "the test exceeded its timeout"))
        test.addScreenshot(ScreenshotInfo("Alice", "shot", "late.png", 1, 1), 1)
        test.stepFinished(step(1, 0).copy(status = StepStatus.PASSED, screenshot = "late.png"))
        test.blockFinished(0)
        test.finish(TestOutcome(TestStatus.ERROR, TestFailurePhase.TIMEOUT, "the test exceeded its timeout"))
        val result = test.build()
        assertEquals(StepStatus.FAILED, result.steps.single().status)
        assertEquals(emptyList<ScreenshotInfo>(), result.screenshots)
    }
}
