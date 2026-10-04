package party.morino.fukurou.engine.paper

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.net.Http
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.model.ProvisionRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/** エージェントの導入（v3 設計 §1.1）。エージェントの jar は偽物を差し込む。 */
class PaperAgentInstallTest {
    @TempDir
    lateinit var dir: Path

    /** Paper の API の代わり（ビルド 232 だけを返す）。 */
    private val api: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val builds = checkNotNull(PaperAgentInstallTest::class.java.getResource("/fixtures/paper-builds.json")).readText()
        val build = Json.parseToJsonElement(builds).jsonArray.first().toString()
        createContext("/versions/1.21.4/builds/232") { exchange ->
            val body = build.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    /** freePort が返すポート（呼ぶたびに増やす）。 */
    private val ports = AtomicInteger(40000)

    private val services = object : PlatformServices {
        override val config: FukurouConfig = FukurouConfig.fromSources(emptyMap(), emptyMap())
        override val cacheDir: Path get() = dir.resolve("cache")
        override suspend fun download(url: String, destination: Path, sha256: String?, headers: Map<String, String>) = destination
        override suspend fun <T> withCacheLock(path: Path, block: suspend () -> T): T = block()
        override fun freePort(): Int = ports.getAndIncrement()
        override fun log(message: String) {}
        override fun warn(message: String) {}
    }

    @AfterEach
    fun stop() {
        api.stop(0)
    }

    /** 偽の jar を持つ PaperPlatform。 */
    private fun platform(agent: Boolean, jar: () -> InputStream?): PaperPlatform =
        PaperPlatform(
            Paper("1.21.4").copy(build = 232, agent = agent),
            services,
            PaperApi(Http(), "http://127.0.0.1:${api.address.port}"),
            jar,
        )

    /** サーバーのディレクトリと今の java で作る準備の依頼。 */
    private fun request(): ProvisionRequest = ProvisionRequest(
        serverDir = dir.resolve("server"),
        sessionIndex = 0,
        plugins = emptyList(),
        serverFiles = null,
        maxPlayers = 20,
        serverHeap = "1G",
        serverJava = Path.of(ProcessHandle.current().info().command().get()),
    )

    @Test
    @DisplayName("Provision copies the agent jar and writes agent.properties with a fresh port and token")
    fun provisionInstallsAgent() {
        val jar = byteArrayOf(0x50, 0x4b, 3, 4, 42)
        val platform = platform(agent = true) { ByteArrayInputStream(jar) }
        val first = runBlocking { platform.provision(request()) }
        val endpoint = checkNotNull(first.agentEndpoint)
        val plugins = dir.resolve("server/plugins")
        assertArrayEquals(jar, Files.readAllBytes(plugins.resolve("fukurou-agent.jar")))
        val properties = Properties().apply { Files.newBufferedReader(plugins.resolve("FukurouAgent/agent.properties")).use(::load) }
        assertEquals(endpoint.port.toString(), properties.getProperty("port"))
        assertEquals(endpoint.token, properties.getProperty("token"))
        assertEquals("127.0.0.1", properties.getProperty("bind"))
        assertTrue(Regex("[0-9a-f]{32}").matches(endpoint.token), endpoint.token)
        // トークンをログに出さない
        assertFalse(endpoint.token in endpoint.toString())
        // セッションごとに新しいポートとトークン
        val second = runBlocking { platform.provision(request()) }
        assertNotEquals(endpoint.token, second.agentEndpoint!!.token)
        assertNotEquals(endpoint.port, second.agentEndpoint!!.port)
    }

    @Test
    @DisplayName("Provision without the agent leaves plugins alone and returns no endpoint")
    fun provisionWithoutAgent() {
        val platform = platform(agent = false) { error("the jar must not be opened") }
        val provisioned = runBlocking { platform.provision(request()) }
        assertNull(provisioned.agentEndpoint)
        assertFalse(Files.exists(dir.resolve("server/plugins/fukurou-agent.jar")))
    }

    @Test
    @DisplayName("A missing agent jar is a SetupException that points at Paper(agent = false), before any download")
    fun missingJar() {
        api.stop(0)
        val error = assertThrows<SetupException> { runBlocking { platform(agent = true) { null }.provision(request()) } }
        assertEquals(PaperAgentInstall.MISSING_MESSAGE, error.message)
        assertTrue("Paper(agent = false)" in error.message.orEmpty())
    }

    @Test
    @DisplayName("The default lookup reads the classpath resource")
    fun defaultLookup() {
        // ビルドの配置によって jar があってもなくてもよい。あれば zip の jar
        PaperAgentInstall.openResource()?.use { stream ->
            val head = stream.readNBytes(2)
            assertArrayEquals(byteArrayOf(0x50, 0x4b), head)
        }
    }
}
