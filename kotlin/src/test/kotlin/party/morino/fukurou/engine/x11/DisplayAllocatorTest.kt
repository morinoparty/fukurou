package party.morino.fukurou.engine.x11

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DisplayAllocatorTest {
    @TempDir
    lateinit var root: Path

    @Test
    @DisplayName("Numbers with a lock file or a socket are skipped and released numbers are reused")
    fun skipsUsedNumbers() {
        Files.createFile(root.resolve(".X99-lock"))
        Files.createDirectories(root.resolve(".X11-unix"))
        Files.createFile(root.resolve(".X11-unix/X100"))
        val allocator = DisplayAllocator(root)
        assertEquals(101, allocator.allocate())
        assertEquals(102, allocator.allocate())
        allocator.release(101)
        assertEquals(101, allocator.allocate())
        assertEquals(103, allocator.allocate(103))
    }

    @Test
    @DisplayName("Concurrent allocations never hand out the same number")
    fun noDuplicates() {
        val allocator = DisplayAllocator(root)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val numbers = pool.invokeAll((1..64).map { Callable { allocator.allocate() } }).map { it.get() }
            assertEquals((99 until 99 + 64).toSet(), numbers.toSet())
        } finally {
            pool.shutdown()
        }
    }
}
