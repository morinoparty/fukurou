package party.morino.fukurou.result.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResultIdsTest {
    @Test
    @DisplayName("Run id joins type, version and label; bad labels are rejected")
    fun runId() {
        assertEquals("paper-26.3-stamp-arena", ResultIds.runId("paper", "26.3", "stamp-arena"))
        assertThrows<IllegalArgumentException> { ResultIds.runId("paper", "26.3", "Stamp") }
        assertThrows<IllegalArgumentException> { ResultIds.runId("paper", "26.3", "a--b") }
        assertThrows<IllegalArgumentException> { ResultIds.checkLabel("a".repeat(41)) }
    }

    @Test
    @DisplayName("Class names become kebab-case labels")
    fun kebab() {
        assertEquals("stamp-arena", ResultIds.kebab("StampArena"))
        assertEquals("http-server2", ResultIds.kebab("HTTPServer2"))
    }

    @Test
    @DisplayName("Colliding run ids get numeric suffixes")
    fun uniqueRunId() {
        assertEquals("a", ResultIds.uniqueRunId("a", emptySet()))
        assertEquals("a-3", ResultIds.uniqueRunId("a", setOf("a", "a-2")))
    }

    @Test
    @DisplayName("Test ids are sanitized, suffixed and deduplicated")
    fun testIds() {
        assertEquals("stamp-thinking-face", ResultIds.sanitizeTestId("stamp thinking face"))
        assertEquals("a-b", ResultIds.sanitizeTestId("--a  b"))
        assertEquals("test", ResultIds.sanitizeTestId("!!"))
        assertEquals("greet-2", ResultIds.invocationId("greet", 2))
        assertThrows<IllegalArgumentException> { ResultIds.checkTestId("-bad") }
        val deduped = ResultIds.dedupeTestIds(listOf("A" to "x", "B" to "x", "A" to "y"))
        assertEquals(listOf("A.x", "B.x", "y"), deduped)
    }
}
