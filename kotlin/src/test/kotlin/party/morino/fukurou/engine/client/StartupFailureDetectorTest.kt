package party.morino.fukurou.engine.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StartupFailureDetectorTest {
    private val openGlFailed = "[15:27:19] [Render thread/ERROR]: Failed to create backend OpenGL\n"
    private val vulkanFailed = "[15:27:19] [Render thread/ERROR]: Failed to create backend Vulkan\n"

    @Test
    @DisplayName("A FATAL line or a crash is reported as is")
    fun fatalLines() {
        assertEquals(
            "[12:00:00] [Render thread/FATAL]: Unreported exception thrown!",
            StartupFailureDetector.detect("[11:59:59] [main/INFO]: ok\n[12:00:00] [Render thread/FATAL]: Unreported exception thrown!\n"),
        )
        assertNotNull(StartupFailureDetector.detect("#@!@# Game crashed! Crash report saved to: crash.txt\n"))
    }

    @Test
    @DisplayName("Every known backend failing means no graphics backend")
    fun allBackendsFailed() {
        val text = javaClass.getResource("/fixtures/lavapipe-26.3-latest.log")!!.readText()
        val message = StartupFailureDetector.detect(text)!!
        assertTrue(message.startsWith("no graphics backend could be created: "))
        assertTrue("OpenGL" in message && "Vulkan" in message && " / " in message)
    }

    @Test
    @DisplayName("One backend failing or one succeeding is not fatal")
    fun oneBackendSucceeded() {
        assertNull(StartupFailureDetector.detect(openGlFailed))
        assertNull(
            StartupFailureDetector.detect(openGlFailed + "[15:27:20] [Render thread/INFO]: Using graphics backend Vulkan\n" + vulkanFailed),
        )
        assertNull(StartupFailureDetector.detect("[12:00:00] [Render thread/INFO]: Connecting to 127.0.0.1, 25565\n"))
    }
}
