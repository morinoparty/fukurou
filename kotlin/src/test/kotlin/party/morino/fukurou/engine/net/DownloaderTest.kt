package party.morino.fukurou.engine.net

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
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.error.SetupException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DownloaderTest {
    private val body = "plugin-bytes".toByteArray()
    private val requests = AtomicInteger()
    private lateinit var server: HttpServer

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun start() {
        // 手元の HTTP サーバーで固定の本文を返す
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file.jar") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    @DisplayName("Downloads through a .part file and moves it into place")
    fun downloads() = runTest {
        val destination = dir.resolve("a/file.jar")
        val result = Downloader().download(url("/file.jar"), destination, Sha256.of(body))
        assertEquals(destination, result)
        assertTrue(body.contentEquals(Files.readAllBytes(destination)))
        assertFalse(Files.exists(dir.resolve("a/file.jar.part")))
    }

    @Test
    @DisplayName("A checksum mismatch leaves neither the file nor the part")
    fun checksumMismatch() = runTest {
        val destination = dir.resolve("file.jar")
        val error = assertThrows<SetupException> { Downloader().download(url("/file.jar"), destination, "0".repeat(64)) }
        assertTrue(error.message!!.contains("checksum mismatch"))
        assertFalse(Files.exists(destination))
        assertFalse(Files.exists(dir.resolve("file.jar.part")))
    }

    @Test
    @DisplayName("A cached file with a matching sha is reused without a request")
    fun reusesCache() = runTest {
        val destination = dir.resolve("file.jar")
        Files.write(destination, body)
        Downloader().download(url("/file.jar"), destination, Sha256.of(body).uppercase())
        assertEquals(0, requests.get())
    }

    @Test
    @DisplayName("An HTTP error fails the download")
    fun httpError() = runTest {
        val error = assertThrows<SetupException> { Downloader().download(url("/missing"), dir.resolve("x.jar")) }
        assertTrue(error.message!!.contains("HTTP 404"))
    }
}
