package party.morino.fukurou.result.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.kind.IsolationMode
import party.morino.fukurou.result.model.suite.ArenaInfo
import party.morino.fukurou.result.model.suite.SuiteInfo
import party.morino.fukurou.result.model.test.LogRange
import party.morino.fukurou.result.model.test.TestResult

class ResultModelSerializationTest {
    /** §6.3 と同じ設定。 */
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    @DisplayName("Arena is written as an object or false and read back")
    fun arenaRoundTrip() {
        val enabled = SuiteInfo(arena = ArenaInfo.Area(32, 24), isolation = IsolationMode.FRESH_SERVER)
        val disabled = SuiteInfo(arena = ArenaInfo.Disabled)
        val enabledText = json.encodeToString(SuiteInfo.serializer(), enabled)
        val disabledText = json.encodeToString(SuiteInfo.serializer(), disabled)
        assertEquals("""{"size":32,"height":24}""", json.parseToJsonElement(enabledText).jsonObject["arena"].toString())
        assertEquals("\"fresh-server\"", json.parseToJsonElement(enabledText).jsonObject["isolation"].toString())
        assertEquals("false", json.parseToJsonElement(disabledText).jsonObject["arena"].toString())
        assertEquals(enabled, json.decodeFromString(SuiteInfo.serializer(), enabledText))
        assertEquals(disabled, json.decodeFromString(SuiteInfo.serializer(), disabledText))
    }

    @Test
    @DisplayName("LogRange uses the 'from' key")
    fun logRangeKey() {
        assertEquals("""{"from":3,"to":9}""", json.encodeToString(LogRange.serializer(), LogRange(3, 9)))
    }

    @Test
    @DisplayName("A skipped test requires a reason")
    fun skippedNeedsReason() {
        assertThrows<IllegalArgumentException> {
            TestResult(id = "a", name = "a", order = 0, source = "junit:A#a", sha256 = "0", status = TestStatus.SKIPPED)
        }
    }
}
