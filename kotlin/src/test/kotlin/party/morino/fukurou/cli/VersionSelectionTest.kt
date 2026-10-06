package party.morino.fukurou.cli

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.engine.paper.PaperBuildPicker
import party.morino.fukurou.server.paper.PaperChannel

/** バージョンの選択のケース（通信しない）。 */
class VersionSelectionTest {
    /** マニフェストのリリース（新しい順）。スナップショットと rc は release_ids で落ちている。 */
    private val releases = listOf("26.3", "26.2", "26.1.2", "1.21.11", "1.20.1", "1.20", "1.19.4", "1.12.2")

    /** Paper のプロジェクトのバージョン（グループを平らにしたもの）。 */
    private val paperVersions = setOf("26.3", "26.3-rc-3", "26.2", "26.1.2", "1.21.11", "1.20.1", "1.20", "1.19.4", "1.12.2")

    /** バージョンごとのビルドのチャンネル（無ければ STABLE だけ）。 */
    private val channels: Map<String, List<String>> = mapOf(
        "26.3" to listOf("ALPHA"),
        "26.2" to listOf("STABLE"),
        "26.1.2" to listOf("STABLE"),
        "1.21.11" to listOf("STABLE"),
        "1.20.1" to listOf("STABLE"),
        "1.20" to listOf("BETA"),
    )

    private suspend fun select(
        spec: String,
        maxVersions: Int = 16,
        channels: Map<String, List<String>> = this.channels,
        paperChannel: String = "stable",
    ): List<String> = VersionSelection.select(
        spec,
        releases,
        paperVersions,
        { version, threshold -> channels.getOrElse(version) { listOf("STABLE") }.any { PaperBuildPicker.channelAccepted(it, threshold) } },
        maxVersions,
        paperChannel,
    )

    /** VersionError のメッセージが正規表現に当たることを確かめる。 */
    private suspend fun assertError(pattern: String, block: suspend () -> Unit) {
        val error = try {
            block()
            null
        } catch (error: VersionError) {
            error
        }
        assertTrue(error != null, "expected a VersionError matching '$pattern'")
        assertTrue(Regex(pattern).containsMatchIn(error!!.message!!), "'${error.message}' does not match '$pattern'")
    }

    @Test
    @DisplayName("latest skips snapshots and unstable Paper builds")
    fun latestSkipsUnstable() = runTest {
        assertEquals(listOf("26.2"), select("latest"))
        assertError("no Minecraft release has a stable Paper build") {
            select("latest", channels = releases.associateWith { listOf("BETA") })
        }
    }

    @Test
    @DisplayName("A single version needs a build in the accepted channels")
    fun singleNeedsAcceptedBuild() = runTest {
        // 26.3 は ALPHA しかないので、既定の stable と beta では使えず、--paper-channel を案内する
        for (channel in listOf("stable", "beta")) {
            assertError("use --paper-channel .*alpha") { select("26.3", paperChannel = channel) }
        }
        assertEquals(listOf("26.3"), select("26.3", paperChannel = "alpha"))
        assertEquals(listOf("1.20"), select("1.20", paperChannel = "beta"))
        assertError("pre-releases and snapshots are not supported") { select("26.3-rc-3", paperChannel = "alpha") }
    }

    @Test
    @DisplayName("The channel threshold widens latest and ranges")
    fun thresholdWidens() = runTest {
        assertEquals(listOf("26.2"), select("latest", paperChannel = "beta"))
        assertEquals(listOf("26.3"), select("latest", paperChannel = "alpha"))
        assertEquals(listOf("1.21.11", "26.1.2", "26.2", "26.3"), select("1.21.11-", paperChannel = "alpha"))
        // beta は BETA の 1.20 を含めるが、ALPHA の 26.3 は含めない
        assertEquals(listOf("1.20", "1.20.1", "1.21.11", "26.1.2", "26.2"), select("1.20-", paperChannel = "beta"))
        assertEquals(listOf("1.20.1", "1.21.11", "26.1.2", "26.2"), select("1.20-", paperChannel = "stable"))
        assertError("has a stable/beta Paper build") { select("26.3-", paperChannel = "beta") }
        assertError("unknown Paper channel 'rc'; expected one of stable, beta, alpha") { select("latest", paperChannel = "rc") }
    }

    @Test
    @DisplayName("Ranges are ordered oldest first by manifest order")
    fun manifestOrder() = runTest {
        // 1.21.11 と 26.1.2 のように番号体系が変わっても、マニフェストの並びで範囲を決める
        assertEquals(listOf("1.21.11", "26.1.2", "26.2"), select("1.21.11-"))
        assertEquals(listOf("1.20.1", "1.21.11", "26.1.2"), select("1.20-26.1.2"))
        assertEquals(listOf("1.20.1", "1.21.11", "26.1.2"), select(" 1.20 - 26.1.2 "))
        assertError("1.20.1 is not a Minecraft release|newer than") { select("26.2-26.1.2") }
        assertError("26.2 is newer than 26.1.2") { select("26.2-26.1.2") }
    }

    @Test
    @DisplayName("Versions older than the minimum are rejected")
    fun minimum() = runTest {
        for (spec in listOf("1.19.4", "1.12.2-", "1.19.4-1.21.11")) {
            assertError("fukurou supports Minecraft 1.20 or later; got") { select(spec) }
        }
    }

    @Test
    @DisplayName("max versions limits ranges")
    fun maxVersions() = runTest {
        assertEquals(4, select("1.20-", maxVersions = 4).size)
        assertError("resolves to 4 versions, more than the maximum of 3") { select("1.20-", maxVersions = 3) }
        // 単一の指定には上限を当てない
        assertEquals(listOf("26.2"), select("26.2", maxVersions = 1))
    }

    @Test
    @DisplayName("Invalid specs are input errors")
    fun invalidSpecs() = runTest {
        assertError("the version spec is empty") { select("  ") }
        assertError("a version range needs a lower bound") { select("-1.21.11") }
        assertError("1.21.99 is not a Minecraft release in the Mojang version manifest") { select("1.21.99-") }
        assertError("is not a Minecraft release in the Mojang version manifest") { select("1.21.6-1.21.11-") }
        assertError("Paper has no builds for 1.21.99|not a Minecraft release") { select("1.21.99") }
    }

    @Test
    @DisplayName("A release Paper never built is not queried and fails as a single version")
    fun releaseWithoutPaper() = runTest {
        val queried = mutableListOf<String>()
        val result = VersionSelection.select("1.20-", listOf("26.4", "26.2", "1.20"), setOf("26.2", "1.20"), { version, _ ->
            queried += version
            true
        })
        assertEquals(listOf("1.20", "26.2"), result)
        assertEquals(listOf("26.2", "1.20"), queried)
        assertError("Paper has no builds for 26.4") {
            VersionSelection.select("26.4", listOf("26.4", "1.20"), setOf("1.20"), { _, _ -> true })
        }
    }

    @Test
    @DisplayName("A version counts when an older build is accepted")
    fun olderBuildAccepted() = runTest {
        // 最新ビルドが ALPHA でも、古い STABLE ビルドがあれば stable で選べる
        val withStable = channels + ("26.3" to listOf("ALPHA", "STABLE"))
        assertEquals(listOf("26.3"), select("26.3", channels = withStable))
        assertEquals(listOf("26.3"), select("latest", channels = withStable))
        assertEquals(listOf("26.2", "26.3"), select("26.2-", channels = withStable))
    }

    @Test
    @DisplayName("The no-build message names the CLI option and the looser channels")
    fun noBuildMessage() {
        assertEquals(
            "Paper has no STABLE/BETA build for 26.3 (--paper-channel beta); " +
                "use --paper-channel alpha (the paper-channel input) to accept less stable builds",
            VersionSelection.noAcceptedBuildMessage("26.3", PaperChannel.Beta),
        )
        assertEquals(
            "Paper has no STABLE build for 26.3 (--paper-channel stable); " +
                "use --paper-channel beta or alpha (the paper-channel input) to accept less stable builds",
            VersionSelection.noAcceptedBuildMessage("26.3", PaperChannel.Stable),
        )
        assertEquals(
            "Paper has no STABLE/BETA/ALPHA build for 26.3 (--paper-channel alpha)",
            VersionSelection.noAcceptedBuildMessage("26.3", PaperChannel.Alpha),
        )
    }
}
