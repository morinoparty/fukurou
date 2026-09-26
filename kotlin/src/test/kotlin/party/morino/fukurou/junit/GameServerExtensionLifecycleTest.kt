package party.morino.fukurou.junit

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.listeners.TestExecutionSummary
import party.morino.fukurou.Fukurou
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.net.MojangApi
import party.morino.fukurou.engine.process.HostCheck
import party.morino.fukurou.junit.fixture.FakeArenaTests
import party.morino.fukurou.junit.fixture.TwinArenaTests
import party.morino.fukurou.junit.fixture.platform.FakeEnvironment
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * 拡張の一連の流れ（beforeAll → beforeEach → 本体 → afterEach → ルートストアの close）を、シェルを起動する偽の種類で
 * 端から端まで確かめる。Minecraft・Xvfb・ネットワークは使わない。
 */
class GameServerExtensionLifecycleTest {
    @TempDir
    lateinit var dir: Path

    /** 手元の Mojang（Java の検査が使うバージョン情報）。 */
    private lateinit var mojang: HttpServer

    /** 元のホストの確認。 */
    private val originalProbe = HostCheck.probe

    @BeforeEach
    fun setUp() {
        mojang = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${mojang.address.port}"
        serve("/manifest.json", """{"versions":[{"id":"1.21.4","type":"release","url":"$base/1.21.4.json"}]}""")
        serve("/1.21.4.json", """{"javaVersion":{"majorVersion":21}}""")
        mojang.start()
        val config = FukurouConfig.fromSources(
            mapOf(
                "fukurou.acceptEula" to "true",
                "fukurou.workDir" to dir.resolve("work").toString(),
                "fukurou.outDir" to dir.resolve("out").toString(),
                "fukurou.memoryBudgetMb" to "100000",
            ),
            emptyMap(),
        )
        FakeEnvironment.fukurou = Fukurou(config, MojangApi(manifestUrl = "$base/manifest.json"))
        FakeEnvironment.commands.clear()
        // Xvfb などが無いホストでも偽の種類は起動できる
        HostCheck.probe = { null }
    }

    @AfterEach
    fun tearDown() {
        HostCheck.probe = originalProbe
        mojang.stop(0)
    }

    @Test
    @DisplayName("runs passing, failing and version-disabled tests and writes the final result.json")
    fun lifecycle() {
        val summary = execute(FakeArenaTests::class.java)
        assertEquals(1, summary.testsSucceededCount, summary.failures.joinToString { it.exception.toString() })
        assertEquals(1, summary.testsFailedCount)
        assertEquals(1, summary.testsSkippedCount)

        val result = onlyResult()
        assertEquals("failed", result.string("status"))
        assertEquals("junit:party.morino.fukurou.junit.fixture.FakeArena", result["suite"]!!.jsonObject.string("source"))
        val tests = result["tests"]!!.jsonArray.map { it.jsonObject }.associateBy { it.string("id") }
        assertEquals("passed", tests.getValue("sends-a-command").string("status"))
        assertEquals("failed", tests.getValue("fails-on-an-error-response").string("status"))
        val disabled = tests.getValue("runs-only-on-a-future-version")
        assertEquals("skipped", disabled.string("status"))
        assertEquals("versions: 99.0- does not include 1.21.4", disabled.string("skipReason"))
        // setUp のコマンドは beforeEach の層で記録される
        val steps = tests.getValue("sends-a-command")["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("beforeEach" to "setup", "test" to "say hi"), steps.map { it.string("phase") to it.string("label") })
        // ルートストアの close でセッションが閉じ、終了時刻が入る
        val session = result["sessions"]!!.jsonArray.single().jsonObject
        assertTrue(session["finishedAt"] is JsonPrimitive && session.string("finishedAt") != null)
        assertTrue(FakeEnvironment.commands.containsAll(listOf("setup", "say hi", "fail now")))
    }

    @Test
    @DisplayName("resolves each extension by type and refuses an ambiguous GameServer parameter")
    fun twoServers() {
        val summary = execute(TwinArenaTests::class.java)
        assertEquals(1, summary.testsSucceededCount, summary.failures.joinToString { it.exception.toString() })
        val failure = summary.failures.single().exception.message.orEmpty()
        assertTrue("declare the parameter as FakeArena or TwinArena" in failure, failure)
        // 2 台とも result.json を書く
        assertEquals(2, dir.resolve("out").listDirectoryEntries().count { Files.exists(it.resolve("result.json")) })
    }

    /** テストクラスを入れ子のランチャーで実行する。 */
    private fun execute(testClass: Class<*>): TestExecutionSummary {
        val listener = SummaryGeneratingListener()
        val request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(testClass)).build()
        LauncherFactory.create().execute(request, listener)
        return listener.summary
    }

    /** 出力先にある唯一の result.json。 */
    private fun onlyResult(): JsonObject {
        val runs = dir.resolve("out").listDirectoryEntries().filter { it.isDirectory() && Files.exists(it.resolve("result.json")) }
        return Json.parseToJsonElement(runs.single().resolve("result.json").readText()).jsonObject
    }

    /** 手元の HTTP サーバーに JSON を置く。 */
    private fun serve(path: String, body: String) {
        mojang.createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    /** 文字列のフィールド（null なら null）。 */
    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
