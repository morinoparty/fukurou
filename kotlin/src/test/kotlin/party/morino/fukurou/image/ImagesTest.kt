package party.morino.fukurou.image

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.player.Screenshot
import party.morino.fukurou.result.RunRecorder
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.run.FukurouInfo
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.suite.SelectionInfo
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

class ImagesTest {
    @TempDir
    lateinit var dir: Path

    /** 記録だけをする偽のサーバー。 */
    private class FakeHost : StepHost {
        override val resultId: String = "paper-26.3-test"
        val recorder = RunRecorder(resultId, "test", MinecraftInfo("26.3"), FukurouInfo("dev", "5.0.4"), null, SelectionInfo(), writer = null)
        override val observer: RunRecorder get() = recorder
        override val harnessScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val warnings = mutableListOf<String>()

        override fun log(message: String) {}

        override fun warn(message: String) {
            warnings += message
        }

        override fun checkClientsAlive() {}

        override fun deadClient(): ClientDiedException? = null

        override fun serverDied(error: ServerUnavailableException) {}
    }

    private val host = FakeHost()

    private val hostB = FakeHost()

    @AfterEach
    fun tearDown() {
        host.harnessScope.cancel()
        hostB.harnessScope.cancel()
    }

    private fun begin(on: FakeHost = host): TestRun {
        on.recorder.runPlanned(listOf(PlannedTest("A", "t", "t", "t", emptyList(), 0, "junit:A#t", "0")))
        on.recorder.sessionStarted(0, SessionKind.INITIAL)
        on.recorder.testStarted("t", 0, emptyList())
        return TestRun(on, "t", 0, 1.minutes)
    }

    /** 単色の画像。 */
    private fun solid(width: Int, height: Int, rgb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, rgb)
        }

    /** 画像を PNG に書いてスクリーンショットにする。 */
    private fun screenshot(image: BufferedImage, name: String = "shot"): Screenshot {
        val path = dir.resolve("tests/t/screenshots/Alice/$name.png")
        Files.createDirectories(path.parent)
        ImageIO.write(image, "png", path.toFile())
        return Screenshot("Alice", name, path, "tests/t/screenshots/Alice/$name.png", image.width, image.height)
    }

    @Test
    @DisplayName("Rgb distance is the largest channel difference")
    fun distance() {
        assertEquals(30, Rgb(10, 20, 30).distanceTo(Rgb(40, 25, 30)))
        assertEquals(Rgb(0x12, 0x34, 0x56), Rgb.of(0x7F123456))
    }

    @Test
    @DisplayName("Identical and within-tolerance images have no different pixels")
    fun identical() {
        val diff = ImageComparison.compare(solid(4, 3, 0x102030), solid(4, 3, 0x112535), channelTolerance = 5)
        assertEquals(0, diff.differentPixels)
        assertEquals(12, diff.totalPixels)
        assertEquals(0.0, diff.ratio)
        // 同じピクセルは actual を暗くした色
        assertEquals(Rgb(0x10 / 3, 0x20 / 3, 0x30 / 3), Rgb.of(diff.diffImage.getRGB(0, 0)))
    }

    @Test
    @DisplayName("Pixels beyond the tolerance are red; ignored regions are grey and not counted")
    fun differentAndIgnored() {
        val actual = solid(4, 4, 0x000000)
        actual.setRGB(0, 0, 0xFFFFFF)
        actual.setRGB(3, 3, 0xFFFFFF)
        val diff = ImageComparison.compare(actual, solid(4, 4, 0x000000), ignore = listOf(Region(2, 2, 5, 5)))
        assertEquals(1, diff.differentPixels)
        assertEquals(12, diff.totalPixels)
        assertEquals(Rgb(255, 0, 0), Rgb.of(diff.diffImage.getRGB(0, 0)))
        assertEquals(Rgb(128, 128, 128), Rgb.of(diff.diffImage.getRGB(3, 3)))
        assertEquals(1.0 / 12, diff.ratio)
    }

    @Test
    @DisplayName("Overlapping and out-of-image ignored regions are counted once or not at all")
    fun overlappingIgnore() {
        val actual = solid(4, 4, 0xFFFFFF)
        val ignore = listOf(Region(0, 0, 2, 2), Region(1, 1, 2, 2), Region(10, 10, 3, 3))
        val diff = ImageComparison.compare(actual, solid(4, 4, 0x000000), ignore = ignore)
        // 2x2 と 2x2 の重なり 1 ピクセルで 7 ピクセルを無視する。画像の外の領域は何もしない
        assertEquals(9, diff.totalPixels)
        assertEquals(9, diff.differentPixels)
        assertEquals(Rgb(128, 128, 128), Rgb.of(diff.diffImage.getRGB(2, 2)))
        assertEquals(Rgb(255, 0, 0), Rgb.of(diff.diffImage.getRGB(3, 3)))
    }

    @Test
    @DisplayName("Images of different sizes are entirely different")
    fun sizeMismatch() {
        val diff = ImageComparison.compare(solid(4, 2, 0), solid(2, 4, 0))
        assertEquals(8, diff.differentPixels)
        assertEquals(1.0, diff.ratio)
    }

    @Test
    @DisplayName("Screenshots expose pixels and region averages")
    fun pixels() {
        val image = solid(4, 2, 0x000000)
        image.setRGB(0, 0, 0xFF0000)
        image.setRGB(1, 0, 0x00FF00)
        val shot = screenshot(image)
        assertEquals(4, shot.image().width)
        assertEquals(Rgb(255, 0, 0), shot.pixel(0, 0))
        assertEquals(Rgb(128, 128, 0), shot.averageColor(Region(0, 0, 2, 1)))
        assertThrows<IllegalArgumentException> { shot.pixel(4, 0) }
        assertThrows<IllegalArgumentException> { shot.averageColor(Region(3, 0, 2, 1)) }
    }

    @Test
    @DisplayName("A missing baseline is written from the screenshot and the step passes with a warning")
    fun missingBaseline() {
        val run = begin()
        val shot = screenshot(solid(4, 4, 0x336699))
        val baseline = dir.resolve("baselines/nested/hud.png")
        runBlocking(StepScope(run)) { Images.assertMatches(shot, baseline, 0.02, 16, emptyList(), update = false) }
        assertTrue(Files.exists(baseline))
        assertEquals(Rgb(0x33, 0x66, 0x99), Rgb.of(ImageIO.read(baseline.toFile()).getRGB(0, 0)))
        assertTrue(host.warnings.any { it.contains("did not exist") }, host.warnings.toString())
        val step = host.recorder.build().tests.single().steps.single()
        assertEquals(Triple("Alice", "compare_screenshot", "hud.png"), Triple(step.on, step.action, step.label))
        assertEquals(StepStatus.PASSED, step.status)
    }

    @Test
    @DisplayName("A mismatch writes <name>.diff.png next to the screenshot and fails the step")
    fun mismatch() {
        val run = begin()
        val baseline = dir.resolve("hud.png")
        ImageIO.write(solid(4, 4, 0x000000), "png", baseline.toFile())
        val shot = screenshot(solid(4, 4, 0xFFFFFF))
        val error = assertThrows<AssertionError> {
            runBlocking(StepScope(run)) { Images.assertMatches(shot, baseline, 0.02, 16, emptyList(), update = false) }
        }
        assertTrue(error.message!!.contains("16/16 pixels"), error.message)
        val diffFile = shot.path.resolveSibling("shot.diff.png")
        assertTrue(Files.exists(diffFile))
        assertEquals(StepStatus.FAILED, host.recorder.build().tests.single().steps.single().status)
    }

    @Test
    @DisplayName("The comparison is recorded on the test of the server that took the screenshot")
    fun twoServers() {
        val runA = begin()
        val runB = begin(on = hostB)
        val baseline = dir.resolve("hud.png")
        ImageIO.write(solid(4, 4, 0x000000), "png", baseline.toFile())
        val shot = screenshot(solid(4, 4, 0xFFFFFF)).also { it.origin = hostB }
        ActiveTests.begin(runA, "owner")
        ActiveTests.begin(runB, "owner")
        try {
            // JUnit のテスト本体から（StepScope 無し）と、A のテストのスコープの中から
            assertThrows<AssertionError> { runBlocking { Images.assertMatches(shot, baseline, 0.02, 16, emptyList(), update = false) } }
            assertThrows<AssertionError> {
                runBlocking(StepScope(runA)) { Images.assertMatches(shot, baseline, 0.02, 16, emptyList(), update = false) }
            }
        } finally {
            ActiveTests.finish(runA)
            ActiveTests.finish(runB)
        }
        assertTrue(host.recorder.build().tests.single().steps.isEmpty())
        val steps = hostB.recorder.build().tests.single().steps
        assertEquals(listOf("compare_screenshot", "compare_screenshot"), steps.map { it.action })
        assertTrue(steps.all { it.status == StepStatus.FAILED })
    }

    @Test
    @DisplayName("Matching within the ratio passes; updateBaselines overwrites the baseline")
    fun matchAndUpdate() {
        val baseline = dir.resolve("hud.png")
        ImageIO.write(solid(10, 10, 0x000000), "png", baseline.toFile())
        val image = solid(10, 10, 0x000000).also { it.setRGB(0, 0, 0xFFFFFF) }
        val shot = screenshot(image)
        runBlocking { Images.assertMatches(shot, baseline, 0.02, 16, emptyList(), update = false) }
        assertFalse(Files.exists(Images.diffPath(shot)))
        runBlocking { Images.assertMatches(shot, baseline, 0.0, 16, emptyList(), update = true) }
        assertEquals(Rgb(255, 255, 255), Rgb.of(ImageIO.read(baseline.toFile()).getRGB(0, 0)))
    }

    @Test
    @DisplayName("updateBaselines reads the property first, then the environment")
    fun updateFlag() {
        assertTrue(Images.updateBaselines("true", null))
        assertTrue(Images.updateBaselines(null, "TRUE"))
        assertFalse(Images.updateBaselines("false", "true"))
        assertFalse(Images.updateBaselines(null, null))
    }
}
