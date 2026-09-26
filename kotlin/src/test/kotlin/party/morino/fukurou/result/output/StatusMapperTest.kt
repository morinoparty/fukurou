package party.morino.fukurou.result.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.InputException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.log.LogAssertionError
import party.morino.fukurou.result.StepEvent
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.TestFailurePhase
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.step.StepPhase
import java.time.Instant
import java.util.concurrent.TimeoutException

class StatusMapperTest {
    private val fixtureStep = StepEvent(7, StepPhase.FIXTURE, "arena", "server", "command", "tp", Instant.EPOCH)

    @Test
    @DisplayName("No exception is passed and an aborted test is skipped")
    fun passedAndSkipped() {
        assertEquals(TestOutcome(TestStatus.PASSED), StatusMapper.outcome(null))
        val skipped = StatusMapper.outcome(TestAbortedException("server died during a"))
        assertEquals(TestStatus.SKIPPED, skipped.status)
        assertEquals("server died during a", skipped.skipReason)
    }

    @Test
    @DisplayName("Assertion errors fail with the step's phase, else the lease phase")
    fun assertions() {
        val onStep = StatusMapper.outcome(LogAssertionError("no match"), fixtureStep)
        assertEquals(TestOutcome(TestStatus.FAILED, TestFailurePhase.FIXTURE, "no match", 7), onStep)
        val noStep = StatusMapper.outcome(AssertionError("x"), leasePhase = StepPhase.BEFORE_EACH)
        assertEquals(TestFailurePhase.BEFORE_EACH, noStep.failurePhase)
        assertNull(noStep.provisionalStepId)
    }

    @Test
    @DisplayName("Client death, timeouts and reset errors are harness errors")
    fun harnessErrors() {
        assertEquals(TestFailurePhase.CLIENT, StatusMapper.outcome(ClientDiedException("Alice", "died")).failurePhase)
        assertEquals(TestFailurePhase.CLIENT, StatusMapper.outcome(InputException("xdotool"), clientDead = true).failurePhase)
        assertEquals(TestFailurePhase.SCENARIO, StatusMapper.outcome(InputException("xdotool")).failurePhase)
        assertEquals(TestFailurePhase.TIMEOUT, StatusMapper.outcome(HarnessTimeoutException("late")).failurePhase)
        assertEquals(TestFailurePhase.TIMEOUT, StatusMapper.outcome(TimeoutException("junit")).failurePhase)
        assertEquals(TestOutcome(TestStatus.ERROR, TestFailurePhase.RESET, "bad"), StatusMapper.outcome(null, resetError = "bad"))
    }

    @Test
    @DisplayName("A dead server fails the test and the run; other exceptions fail the test in their phase")
    fun serverAndOther() {
        val died = ServerUnavailableException("rcon refused")
        assertEquals(TestOutcome(TestStatus.FAILED, TestFailurePhase.SCENARIO, "rcon refused"), StatusMapper.outcome(died))
        val run = StatusMapper.runFailure(died, "a")
        assertEquals(RunFailurePhase.SERVER, run?.phase)
        assertEquals("server died during a: rcon refused", run?.message)
        assertNull(StatusMapper.runFailure(AssertionError(), "a"))
        val other = StatusMapper.outcome(IllegalStateException("oops"))
        assertEquals(TestOutcome(TestStatus.FAILED, TestFailurePhase.SCENARIO, "IllegalStateException: oops"), other)
        // 層は失敗したステップ（無ければテストが居た層）から決める
        val inSetUp = StatusMapper.outcome(IllegalStateException("oops"), leasePhase = StepPhase.BEFORE_EACH)
        assertEquals(TestOutcome(TestStatus.FAILED, TestFailurePhase.BEFORE_EACH, "IllegalStateException: oops"), inSetUp)
    }
}
