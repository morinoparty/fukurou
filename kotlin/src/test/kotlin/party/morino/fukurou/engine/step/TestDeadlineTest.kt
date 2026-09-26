package party.morino.fukurou.engine.step

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.HarnessTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class TestDeadlineTest {
    @Test
    @DisplayName("A running deadline has time left and passes the check")
    fun running() {
        val deadline = TestDeadline(10.minutes)
        assertFalse(deadline.isExceeded)
        assertTrue(deadline.remaining() > 9.minutes)
        assertDoesNotThrow { deadline.check(0) }
    }

    @Test
    @DisplayName("An exceeded deadline has no time left and names the step")
    fun exceeded() {
        val deadline = TestDeadline(Duration.ZERO)
        assertTrue(deadline.isExceeded)
        assertEquals(Duration.ZERO, deadline.remaining())
        val error = assertThrows<HarnessTimeoutException> { deadline.check(3) }
        assertEquals("the test exceeded its timeout of 0s before step 3", error.message)
    }
}
