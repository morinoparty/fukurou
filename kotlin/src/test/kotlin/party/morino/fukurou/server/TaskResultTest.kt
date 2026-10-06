package party.morino.fukurou.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** execute の戻り値の読み方（decodeTaskResult）を確かめる。 */
class TaskResultTest {
    @Test
    @DisplayName("Unit ignores the value, including JSON null")
    fun unit() {
        assertEquals(Unit, decodeTaskResult<Unit>(JsonNull))
        assertEquals(Unit, decodeTaskResult<Unit>(JsonPrimitive(3)))
    }

    @Test
    @DisplayName("nullable and plain types decode with kotlinx.serialization")
    fun values() {
        assertNull(decodeTaskResult<Int?>(JsonNull))
        assertEquals(3, decodeTaskResult<Int>(JsonPrimitive(3)))
        assertEquals(listOf("Alice"), decodeTaskResult<List<String>>(buildJsonArray { add(JsonPrimitive("Alice")) }))
    }
}
