package party.morino.fukurou.engine.paper

import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.engine.net.Http
import party.morino.fukurou.server.paper.PaperChannel
import party.morino.fukurou.version.MinecraftVersion

class PaperBuildPickerTest {
    private val builds: List<PaperBuild> = Http.JSON.decodeFromString(
        ListSerializer(PaperBuild.serializer()),
        checkNotNull(javaClass.getResource("/fixtures/paper-builds.json")).readText(),
    )

    @Test
    @DisplayName("Picks the highest build whose channel the threshold accepts")
    fun picksByThreshold() {
        assertEquals(230, PaperBuildPicker.pick(builds, PaperChannel.Stable)?.id)
        assertEquals(231, PaperBuildPicker.pick(builds, PaperChannel.Beta)?.id)
        assertEquals(232, PaperBuildPicker.pick(builds, PaperChannel.Alpha)?.id)
        assertEquals("paper-1.21.4-230.jar", PaperBuildPicker.pick(builds, PaperChannel.Stable)?.serverDownload()?.name)
    }

    @Test
    @DisplayName("Returns null when no build is accepted and builds the channel query")
    fun noneAccepted() {
        assertNull(PaperBuildPicker.pick(builds.filter { it.channel != "STABLE" }, PaperChannel.Stable))
        assertEquals("channel=STABLE&channel=BETA", PaperBuildPicker.channelQuery(PaperChannel.Beta))
    }

    @Test
    @DisplayName("The no-accepted message names the looser channels only when there are some")
    fun noAcceptedMessage() {
        val version = MinecraftVersion("26.3")
        assertEquals(
            "Paper has no STABLE build for 26.3 (fukurou.paperChannel=stable); " +
                "pass -Pfukurou.paperChannel=beta|alpha to accept less stable builds",
            PaperBuildPicker.noAcceptedBuildMessage(version, PaperChannel.Stable),
        )
        assertEquals(
            "Paper has no STABLE/BETA/ALPHA build for 26.3 (fukurou.paperChannel=alpha)",
            PaperBuildPicker.noAcceptedBuildMessage(version, PaperChannel.Alpha),
        )
    }
}
