package party.morino.fukurou.engine.paper

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.server.paper.PaperChannel
import party.morino.fukurou.version.MinecraftVersion
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class PaperApiTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        // fill v3 の応答を真似る手元のサーバー
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/paper") { exchange ->
            val uri = exchange.requestURI.toString()
            requests += uri
            val body = when {
                uri == "/paper" -> """{"project":{"id":"paper"},"versions":{"26.3":["26.3","26.3-rc-3"],"1.21":["1.21.11"]}}"""
                uri == "/paper/versions/26.3/builds/latest" -> """{"id":40,"channel":"ALPHA","downloads":{}}"""
                uri == "/paper/versions/26.4/builds/latest" -> """{"id":3,"channel":"ALPHA"}"""
                uri == "/paper/versions/26.5/builds/40" -> """{"id":40,"channel":"ALPHA"}"""
                uri.startsWith("/paper/versions/26.5/builds?") ->
                    if ("ALPHA" in uri) """[{"id":40,"channel":"ALPHA"},{"id":20,"channel":"STABLE"}]""" else "[]"
                uri.startsWith("/paper/versions/26.3/builds?") ->
                    // API の channel の絞り込みを真似る
                    if ("STABLE" in uri) """[{"id":20,"channel":"STABLE"}]""" else "[]"
                uri.startsWith("/paper/versions/26.4/builds?") -> "[]"
                else -> null
            }
            val bytes = (body ?: """{"message":"not found"}""").toByteArray()
            exchange.sendResponseHeaders(if (body == null) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun api() = PaperApi(projectUrl = "http://127.0.0.1:${server.address.port}/paper")

    @Test
    @DisplayName("Version ids are the flattened project groups")
    fun versionIds() = runTest {
        assertEquals(setOf("26.3", "26.3-rc-3", "1.21.11"), api().versionIds())
    }

    @Test
    @DisplayName("hasAcceptedBuild looks past the newest build only when needed")
    fun hasAcceptedBuild() = runTest {
        val api = api()
        // 最新ビルドが許されれば一覧は取得しない
        assertTrue(api.hasAcceptedBuild("26.3", PaperChannel.Alpha))
        assertEquals(listOf("/paper/versions/26.3/builds/latest"), requests.toList())
        // 最新が ALPHA でも、絞り込んだ一覧に STABLE があれば stable で使える
        assertTrue(api.hasAcceptedBuild("26.3", PaperChannel.Stable))
        assertTrue(requests.last().endsWith("/26.3/builds?channel=STABLE"))
        assertFalse(api.hasAcceptedBuild("26.4", PaperChannel.Beta))
        assertTrue(requests.last().endsWith("/26.4/builds?channel=STABLE&channel=BETA"))
        assertEquals("ALPHA", api.latestChannel("26.3"))
    }

    @Test
    @DisplayName("resolve filters by channel and keeps an explicit build")
    fun resolveBuild() = runTest {
        val api = api()
        assertEquals(40, api.resolve(MinecraftVersion("26.5"), PaperChannel.Alpha, null).id)
        assertTrue(requests.last().endsWith("/26.5/builds?channel=STABLE&channel=BETA&channel=ALPHA"))
        val error = assertThrows<SetupException> { api.resolve(MinecraftVersion("26.5"), PaperChannel.Beta, null) }
        assertTrue(error.message!!.startsWith("Paper has no STABLE/BETA build for 26.5"), error.message)
        // 明示したビルドはチャンネルに関係なく使う
        assertEquals("ALPHA", api.resolve(MinecraftVersion("26.5"), PaperChannel.Stable, 40).channel)
        assertThrows<SetupException> { api.resolve(MinecraftVersion("26.5"), PaperChannel.Stable, 41) }
    }
}
