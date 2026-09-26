package party.morino.fukurou.engine.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.error.SetupException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class CacheLockTest {
    @TempDir
    lateinit var dir: Path

    @Test
    @DisplayName("Two coroutines on the same path never overlap")
    fun coroutinesSerialize() = runBlocking {
        val target = dir.resolve("cache/item")
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        (1..4).map {
            async(Dispatchers.IO) {
                CacheLock.withLock(target) {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    delay(50)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxInside.get())
    }

    @Test
    @DisplayName("Nested locks on the same path are reentrant")
    fun reentrant() = runBlocking {
        val target = dir.resolve("item")
        val value = CacheLock.withLock(target) { CacheLock.withLock(target) { 7 } }
        assertEquals(7, value)
    }

    @Test
    @DisplayName("Another JVM holding the lock makes this one wait or time out")
    fun otherJvm() = runBlocking {
        val target = dir.resolve("shared")
        val java = ProcessHandle.current().info().command().get()
        val child = ProcessBuilder(
            java, "-Xmx64m", "-cp", System.getProperty("java.class.path"),
            CacheLockHolder::class.java.name, target.toString(), "1500",
        ).redirectErrorStream(true).start()
        try {
            // 子の JVM がロックを取るまで待つ
            val line = child.inputStream.bufferedReader().readLine()
            assertEquals("locked", line)
            // 短い待ちでは取れない
            assertThrows<SetupException> { runBlocking { CacheLock.withLock(target, 200.milliseconds) {} } }
            // 待てば子が離した後に取れる
            val started = TimeSource.Monotonic.markNow()
            CacheLock.withLock(target) {}
            assertTrue(started.elapsedNow() > 300.milliseconds)
        } finally {
            child.waitFor(10, TimeUnit.SECONDS)
            child.destroyForcibly()
        }
    }
}
