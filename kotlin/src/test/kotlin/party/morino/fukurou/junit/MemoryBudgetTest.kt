package party.morino.fukurou.junit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MemoryBudgetTest {
    /** 退避の候補（名前と見積もり、古い順に並べて渡す）。 */
    private data class Server(val name: String, val mb: Long)

    @Test
    @DisplayName("estimates the default two-player arena at about 7.6 GB")
    fun estimate() {
        assertEquals(2048L + 750, MemoryBudget.heapMb("2G") + MemoryBudget.SERVER_OVERHEAD_MB)
        assertEquals(2798L + 2 * 2436, MemoryBudget.estimateMb("2G", "1536M", 2))
        assertEquals(1L, MemoryBudget.heapMb("1048576"))
        assertThrows<IllegalArgumentException> { MemoryBudget.heapMb("two gigs") }
    }

    @Test
    @DisplayName("reads 90 percent of MemAvailable from /proc/meminfo")
    fun memInfo() {
        val text = "MemTotal:       16384000 kB\nMemFree:         1000000 kB\nMemAvailable:   13000000 kB\n"
        assertEquals((13000000L / 1024 * 0.9).toLong(), MemoryBudget.fromMemInfo(text))
        assertNull(MemoryBudget.fromMemInfo("MemTotal: 1 kB"))
    }

    @Test
    @DisplayName("evicts nothing when everything fits")
    fun fits() {
        val idle = listOf(Server("a", 3000))
        val evicted = MemoryBudget(12000).evictions("b", 7600, emptyMap(), idle, { it.mb }, { it.name })
        assertTrue(evicted.isEmpty())
    }

    @Test
    @DisplayName("evicts idle servers least recently used first until the new one fits")
    fun lruOrder() {
        val idle = listOf(Server("oldest", 3000), Server("older", 3000), Server("newest", 3000))
        val evicted = MemoryBudget(12000).evictions("new", 7000, emptyMap(), idle, { it.mb }, { it.name })
        assertEquals(listOf("oldest", "older"), evicted.map { it.name })
    }

    @Test
    @DisplayName("fails fast with every estimate and the property to raise when servers in use do not fit")
    fun failFast() {
        val error = assertThrows<ServerBudgetException> {
            MemoryBudget(11700).evictions("paper-26.3-arena-b", 7670, mapOf("paper-26.3-arena-a" to 7670L), listOf(Server("idle", 1)), { it.mb }, { it.name })
        }
        val message = error.message.orEmpty()
        assertTrue("paper-26.3-arena-a (in use by this test class): 7670 MB" in message, message)
        assertTrue("paper-26.3-arena-b: 7670 MB" in message, message)
        assertTrue("-Pfukurou.memoryBudgetMb" in message, message)
    }
}
