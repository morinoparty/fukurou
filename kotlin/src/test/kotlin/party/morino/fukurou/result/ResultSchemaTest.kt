package party.morino.fukurou.result

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.RunStatus
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.kind.IsolationMode
import party.morino.fukurou.result.model.kind.PluginRole
import party.morino.fukurou.result.model.run.FukurouInfo
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.run.ResultV2
import party.morino.fukurou.result.model.session.LogInfo
import party.morino.fukurou.result.model.session.LogKind
import party.morino.fukurou.result.model.step.ParallelInfo
import party.morino.fukurou.result.model.step.StepPhase
import party.morino.fukurou.result.model.suite.ArenaInfo
import party.morino.fukurou.result.model.suite.PluginInfo
import party.morino.fukurou.result.model.suite.SelectionInfo
import party.morino.fukurou.result.model.suite.SuiteInfo
import party.morino.fukurou.result.model.test.LogRange
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.result.model.test.ScreenshotInfo
import party.morino.fukurou.result.output.ArtifactLayout
import party.morino.fukurou.result.output.ResultIds
import party.morino.fukurou.result.output.StatusMapper
import party.morino.fukurou.spi.model.PlatformInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** 記録係の出来事から作った result.json を契約のスキーマ（../schema/result.v2.json）で検証し、golden を書き出す。 */
class ResultSchemaTest {
    @TempDir
    lateinit var out: Path

    private val t0: Instant = Instant.parse("2026-09-26T03:02:14.120Z")
    private val clock: Clock = Clock.fixed(t0, ZoneOffset.UTC)
    private val alice = PlayerProfile("Alice", op = true)
    private val bob = PlayerProfile("Bob")

    private fun planned(id: String, order: Int, isolation: IsolationMode = IsolationMode.RESET) =
        PlannedTest(
            className = "party.morino.example.StampTest",
            method = id,
            id = id,
            name = id,
            tags = listOf("stamp"),
            order = order,
            source = "junit:party.morino.example.StampTest#$id",
            sha256 = "0".repeat(64),
            isolation = isolation,
            players = listOf(alice, bob),
        )

    private fun step(id: Long, on: String?, action: String, label: String, lane: Int? = null) =
        StepEvent(id, StepPhase.TEST, null, on, action, label, t0.plusMillis(id), lane?.let { ParallelInfo(0, it) })

    private fun StepEvent.done(status: StepStatus = StepStatus.PASSED, error: String? = null, screenshot: String? = null) =
        copy(status = status, finishedAt = startedAt.plusMillis(250), durationMs = 250, error = error, screenshot = screenshot)

    /** 2 セッション・parallel ブロック・skipped のテスト・run の失敗を含む結果を、出来事から組み立てる。 */
    private fun record(layout: ArtifactLayout): ResultV2 {
        val recorder = RunRecorder(
            runId = layout.runId,
            label = "stamp-arena",
            minecraft = MinecraftInfo("26.3"),
            fukurou = FukurouInfo("dev", "5.0.4", "kotlin"),
            suite = SuiteInfo("junit:party.morino.example.StampArena", "1".repeat(64), IsolationMode.RESET, 2.0, "survival", ArenaInfo.Area(32, 24)),
            selection = SelectionInfo(tests = listOf("StampTest"), tags = emptyList()),
            writer = ResultWriter(layout.resultFile),
            env = mapOf("GITHUB_ACTIONS" to "true", "GITHUB_REPOSITORY" to "morinoparty/MineStamp", "GITHUB_RUN_ID" to "1"),
            clock = clock,
        )
        recorder.runPlanned(listOf(planned("greeting", 0), planned("fresh", 1, IsolationMode.FRESH_SERVER), planned("later", 2)))
        recorder.sessionStarted(0, SessionKind.INITIAL)
        recorder.serverReady(
            PlatformInfo("paper", "26.3", 12, "alpha", 25),
            listOf(PluginInfo("MineStamp.jar", "2".repeat(64), "MineStamp", "1.0", PluginRole.UNDER_TEST, "local", 65, true)),
        )
        recorder.playerJoined("Alice")
        recorder.playerJoined("Bob")

        // greeting: 順番のステップ → parallel ブロック（レーン 1 が先に届く）→ スクリーンショット
        recorder.testStarted("greeting", 0, listOf(alice, bob))
        recorder.resetFinished("greeting", ResetInfo(120))
        recorder.stepStarted("greeting", step(1, "server", "command", "say hi"))
        recorder.stepFinished("greeting", step(1, "server", "command", "say hi").done())
        recorder.stepStarted("greeting", step(2, "Bob", "chat", "hello", lane = 1))
        recorder.stepStarted("greeting", step(3, "Alice", "screenshot", "shot", lane = 0))
        recorder.stepFinished("greeting", step(2, "Bob", "chat", "hello", lane = 1).done())
        val shot = layout.screenshot("greeting", "Alice", "shot")
        recorder.screenshotTaken("greeting", ScreenshotInfo("Alice", "shot", shot, 854, 480), 3)
        recorder.stepFinished("greeting", step(3, "Alice", "screenshot", "shot", lane = 0).done(screenshot = shot))
        recorder.blockFinished("greeting", 0)
        recorder.stepStarted("greeting", step(4, "server", "wait_for_log", "stamp sent"))
        recorder.stepFinished("greeting", step(4, "server", "wait_for_log", "stamp sent").done(StepStatus.FAILED, "no line matched"))
        recorder.testFinished(
            "greeting",
            StatusMapper.outcome(AssertionError("no line matched")),
            mapOf(ArtifactLayout.sessionServerLog(0) to LogRange(10, 42)),
        )
        recorder.sessionFinished(0, listOf(LogInfo(LogKind.SERVER, ArtifactLayout.sessionServerLog(0))), null)

        // fresh: 作り直したサーバーで、テストの途中でサーバーが死ぬ
        recorder.sessionStarted(1, SessionKind.FRESH_SERVER)
        recorder.playerJoined("Alice")
        recorder.testStarted("fresh", 1, listOf(alice))
        recorder.stepStarted("fresh", step(1, "server", "command", "stamp give Alice"))
        val died = party.morino.fukurou.error.ServerUnavailableException("rcon refused")
        recorder.stepFinished("fresh", step(1, "server", "command", "stamp give Alice").done(StepStatus.FAILED, "rcon refused"))
        recorder.testFinished("fresh", StatusMapper.outcome(died), emptyMap())
        val runFailure = StatusMapper.runFailure(died, "fresh")!!
        recorder.runFailed(runFailure.phase, runFailure.message)
        recorder.testSkipped("later", StatusMapper.serverDiedReason("fresh"))
        recorder.sessionFinished(1, listOf(LogInfo(LogKind.CLIENT, ArtifactLayout.sessionClientLog(1, "Alice", 1), "Alice")), "server died")
        recorder.runFinished()
        return recorder.write()
    }

    @Test
    @DisplayName("A recorded run validates against result.v2.json and is written as a golden")
    fun validatesAgainstSchema() {
        val layout = ArtifactLayout(out, ResultIds.runId("paper", "26.3", "stamp-arena"))
        layout.prepare()
        val result = record(layout)
        val text = layout.resultFile.readText()

        // スキーマは fukurou の Python モデルから生成した契約（Draft 2020-12）
        val schemaDir = Path.of(System.getProperty("fukurou.schemaDir") ?: "../schema")
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schemaDir.resolve("result.v2.json").readText())
        val errors = schema.validate(ObjectMapper().readTree(text))
        assertTrue(errors.isEmpty(), "schema violations: $errors")
        // 検証が本当に効いていることの確認（契約に無い status は違反になる）
        val broken = ObjectMapper().readTree(text).also { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("status", "bogus") }
        assertTrue(schema.validate(broken).isNotEmpty())

        // 形の確認: parallel のステップはレーン 0 が先、スクリーンショットは付け直した添字を指す
        val greeting = result.tests.first { it.id == "greeting" }
        assertEquals(listOf(null, 0, 1, null), greeting.steps.map { it.parallel?.lane })
        assertEquals(1, greeting.screenshots.single().stepIndex)
        assertEquals(3, greeting.failure?.stepIndex)
        assertEquals(RunStatus.ERROR, result.status)
        assertEquals(listOf(TestStatus.FAILED, TestStatus.FAILED, TestStatus.SKIPPED), result.tests.map { it.status })
        assertEquals(listOf(listOf("greeting"), listOf("fresh")), result.sessions.map { it.tests })
        // 読み戻しても同じ内容になる（camelCase・from・arena の形が往復する）
        assertEquals(result, ResultWriter.JSON.decodeFromString(ResultV2.serializer(), text))
        assertTrue(Files.notExists(layout.resultFile.resolveSibling("result.json.tmp")))

        // pydantic との突き合わせ（§8.4）に使う golden
        val goldenDir = System.getProperty("fukurou.goldenDir")?.let(Path::of) ?: out.resolve("golden")
        goldenDir.createDirectories().resolve("result-full.json").writeText(text)
    }

    @Test
    @DisplayName("The plan stub validates against result.v2.json before the server starts")
    fun stubValidates() {
        val layout = ArtifactLayout(out, "paper-26.3-stub")
        val recorder = RunRecorder(
            runId = layout.runId,
            label = "stub",
            minecraft = MinecraftInfo("26.3"),
            fukurou = RunRecorder.loadFukurouInfo(),
            suite = null,
            selection = SelectionInfo(),
            writer = ResultWriter(layout.resultFile),
            env = emptyMap(),
            clock = clock,
        )
        recorder.runPlanned(listOf(planned("greeting", 0)))
        val text = layout.resultFile.readText()
        val schemaDir = Path.of(System.getProperty("fukurou.schemaDir") ?: "../schema")
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(schemaDir.resolve("result.v2.json").readText())
        assertTrue(schema.validate(ObjectMapper().readTree(text)).isEmpty())
        assertEquals(TestStatus.SKIPPED, recorder.build().tests.single().status)
        val goldenDir = System.getProperty("fukurou.goldenDir")?.let(Path::of) ?: out.resolve("golden")
        goldenDir.createDirectories().resolve("result-stub.json").writeText(text)
    }
}
