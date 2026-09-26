package party.morino.fukurou.engine.net

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.error.SetupException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class GithubReleasesTest {
    private val jar = "asset-bytes".toByteArray()

    /** 各サーバーが受け取った Authorization（無ければ "none"）。 */
    private val authorization = ConcurrentHashMap<String, String>()
    private lateinit var api: HttpServer
    private lateinit var storage: HttpServer

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun start() {
        // 署名付き URL の役（別のポート = 別のホスト）
        storage = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        storage.createContext("/signed/Example.jar") { exchange ->
            authorization["storage"] = exchange.requestHeaders.getFirst("Authorization") ?: "none"
            exchange.sendResponseHeaders(200, jar.size.toLong())
            exchange.responseBody.use { it.write(jar) }
        }
        storage.start()
        // GitHub の API の役
        api = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val apiBase = "http://127.0.0.1:${api.address.port}"
        api.createContext("/repos/owner/repo/releases/tags/v1") { exchange ->
            authorization["release"] = exchange.requestHeaders.getFirst("Authorization") ?: "none"
            val json = """{"assets":[{"id":42,"name":"Example.jar","updated_at":"2026-01-02T03:04:05Z",
                |"url":"$apiBase/assets/42"},{"id":43,"name":"Other.jar","updated_at":"x","url":"$apiBase/assets/43"}]}""".trimMargin()
                .toByteArray()
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        api.createContext("/assets/42") { exchange ->
            authorization["asset"] = exchange.requestHeaders.getFirst("Authorization") ?: "none"
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${storage.address.port}/signed/Example.jar")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        api.start()
    }

    @AfterEach
    fun stop() {
        api.stop(0)
        storage.stop(0)
    }

    private fun releases() = GithubReleases(dir, Downloader(), "http://127.0.0.1:${api.address.port}")

    @Test
    @DisplayName("The token goes to the API but not to the redirect target")
    fun tokenNotForwarded() = runTest {
        val path = releases().asset("owner/repo", "v1", "Example.jar", "secret")
        assertEquals(dir.resolve("42-2026-01-02T03_04_05Z").resolve("Example.jar"), path)
        assertTrue(jar.contentEquals(Files.readAllBytes(path)))
        assertEquals("Bearer secret", authorization["release"])
        assertEquals("Bearer secret", authorization["asset"])
        assertEquals("none", authorization["storage"])
    }

    @Test
    @DisplayName("A cached asset is reused without downloading again")
    fun reusesCache() = runTest {
        releases().asset("owner/repo", "v1", "Example.jar", null)
        authorization.remove("asset")
        releases().asset("owner/repo", "v1", "Example.jar", null)
        assertNull(authorization["asset"])
    }

    @Test
    @DisplayName("A missing asset lists the available names")
    fun missingAsset() = runTest {
        val error = assertThrows<SetupException> { releases().asset("owner/repo", "v1", "Nope.jar", null) }
        assertEquals("owner/repo@v1 has no asset named 'Nope.jar' (assets: Example.jar, Other.jar)", error.message)
    }

    @Test
    @DisplayName("A missing release is reported as NotFound")
    fun missingRelease() = runTest {
        assertThrows<Http.NotFound> { releases().asset("owner/repo", "v2", "Example.jar", null) }
    }
}
