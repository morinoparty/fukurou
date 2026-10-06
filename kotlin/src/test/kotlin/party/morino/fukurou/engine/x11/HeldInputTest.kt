package party.morino.fukurou.engine.x11

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.InputException
import party.morino.fukurou.player.KeySym
import party.morino.fukurou.player.MouseButton
import kotlin.time.Duration.Companion.seconds

class HeldInputTest {
    /** 離した順を覚え、指定のものだけ失敗する偽物のウィンドウ。 */
    private class FakeDevice(private val failing: Set<String> = emptySet()) : HeldInputDevice {
        val released = mutableListOf<String>()

        override suspend fun keyUp(key: KeySym) {
            released += "key ${key.keysym}"
            if (key.keysym in failing) throw InputException("xdotool keyup failed")
        }

        override suspend fun mouseUp(button: MouseButton) {
            released += "button ${button.label}"
            if (button.label in failing) throw InputException("xdotool mouseup failed")
        }
    }

    @Test
    @DisplayName("Releasing at the end of a test lets go of buttons then keys in reverse press order")
    fun releasesEverything() = runTest {
        val held = HeldInput()
        held.keyPressed(KeySym.W)
        held.keyPressed(KeySym.SHIFT)
        held.buttonPressed(MouseButton.LEFT)
        held.buttonPressed(MouseButton.RIGHT)
        val device = FakeDevice()
        assertEquals(emptyList<String>(), held.releaseAll(device))
        assertEquals(
            listOf("button right", "button left", "key Shift_L", "key w"),
            device.released,
        )
        assertTrue(held.isEmpty)
        // 2 回目は何も送らない
        assertEquals(emptyList<String>(), held.releaseAll(device))
        assertEquals(4, device.released.size)
    }

    @Test
    @DisplayName("Keys released during the test are not released again")
    fun forgetsReleased() = runTest {
        val held = HeldInput()
        held.keyPressed(KeySym.W)
        held.keyPressed(KeySym.A)
        held.keyReleased(KeySym.W)
        held.buttonPressed(MouseButton.LEFT)
        held.buttonReleased(MouseButton.LEFT)
        assertEquals(listOf(KeySym.A), held.keys())
        assertEquals(emptyList<MouseButton>(), held.buttons())
        val device = FakeDevice()
        held.releaseAll(device)
        assertEquals(listOf("key a"), device.released)
    }

    @Test
    @DisplayName("A failed release is reported but the rest are still released and the record is cleared")
    fun reportsFailures() = runTest {
        val held = HeldInput()
        held.keyPressed(KeySym.W)
        held.keyPressed(KeySym.SHIFT)
        held.buttonPressed(MouseButton.LEFT)
        val device = FakeDevice(failing = setOf("Shift_L", "left"))
        val failures = held.releaseAll(device)
        assertEquals(listOf("left: xdotool mouseup failed", "Shift_L: xdotool keyup failed"), failures)
        assertEquals(listOf("button left", "key Shift_L", "key w"), device.released)
        assertTrue(held.isEmpty)
    }

    @Test
    @DisplayName("Clearing forgets held input after the client is relaunched")
    fun clears() {
        val held = HeldInput()
        held.keyPressed(KeySym.CONTROL)
        held.buttonPressed(MouseButton.MIDDLE)
        held.clear()
        assertTrue(held.isEmpty)
    }

    @Test
    @DisplayName("Releasing at the end reports nothing when all held input is released, and nothing is held afterwards")
    fun releaseAtEndClean() = runTest {
        val held = HeldInput()
        // 何も押していなければ、ウィンドウが無くても問題にしない
        assertNull(held.releaseAtEnd(null, Mutex(), 30.seconds))
        held.keyPressed(KeySym.W)
        val device = FakeDevice()
        assertNull(held.releaseAtEnd(device, Mutex(), 30.seconds))
        assertEquals(listOf("key w"), device.released)
        assertTrue(held.isEmpty)
    }

    @Test
    @DisplayName("Releasing at the end reports a missing window, a release failure, or a lock that is never freed")
    fun releaseAtEndProblems() = runTest {
        val held = HeldInput()
        held.keyPressed(KeySym.SHIFT)
        assertEquals("the Minecraft window was not found", held.releaseAtEnd(null, Mutex(), 30.seconds))
        assertTrue(held.isEmpty)

        held.keyPressed(KeySym.SHIFT)
        assertEquals("Shift_L: xdotool keyup failed", held.releaseAtEnd(FakeDevice(setOf("Shift_L")), Mutex(), 30.seconds))
        assertTrue(held.isEmpty)

        // 置き去りのレーンがロックを持ち続けている
        held.buttonPressed(MouseButton.LEFT)
        val lock = Mutex().also { it.lock() }
        val device = FakeDevice()
        assertEquals("timed out after 30s", held.releaseAtEnd(device, lock, 30.seconds))
        assertEquals(emptyList<String>(), device.released)
        assertTrue(held.isEmpty)
    }

    @Test
    @DisplayName("releasingAfter releases after success and rethrows a release failure")
    fun releasingAfterSuccess() = runTest {
        val order = mutableListOf<String>()
        val value = HeldInput.releasingAfter(release = { order += "release" }) { order += "block"; 42 }
        assertEquals(42, value)
        assertEquals(listOf("block", "release"), order)
        val error = assertThrows<InputException> {
            HeldInput.releasingAfter<Unit>(release = { throw InputException("keyup failed") }) { }
        }
        assertEquals("keyup failed", error.message)
    }

    @Test
    @DisplayName("releasingAfter keeps the block's failure and attaches the release failure as suppressed")
    fun releasingAfterFailure() = runTest {
        val original = IllegalStateException("block failed")
        val thrown = assertThrows<IllegalStateException> {
            HeldInput.releasingAfter<Unit>(release = { throw InputException("keyup failed") }) { throw original }
        }
        assertSame(original, thrown)
        assertEquals(listOf("keyup failed"), thrown.suppressed.map { it.message })
    }

    @Test
    @DisplayName("releasingAfter still releases when the block is cancelled")
    fun releasingAfterCancelled() = runTest {
        var released = false
        val job = async {
            HeldInput.releasingAfter<Unit>(release = {
                // NonCancellable なので取り消し後でも suspend できる
                yield()
                released = true
            }) { awaitCancellation() }
        }
        yield()
        job.cancel()
        assertThrows<CancellationException> { job.await() }
        assertTrue(released)
    }

    @Test
    @DisplayName("releaseEach keeps releasing after a failure and throws the first one")
    fun releaseEachContinues() = runTest {
        val released = mutableListOf<String>()
        val error = assertThrows<InputException> {
            HeldInput.releaseEach(listOf("a", "b", "c")) {
                released += it
                if (it != "b") throw InputException("$it failed")
            }
        }
        assertEquals(listOf("a", "b", "c"), released)
        assertEquals("a failed", error.message)
        assertEquals(listOf("c failed"), error.suppressed.map { it.message })
    }
}
