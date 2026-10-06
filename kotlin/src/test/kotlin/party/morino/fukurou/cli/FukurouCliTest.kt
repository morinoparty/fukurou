package party.morino.fukurou.cli

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.engine.net.MojangApi
import party.morino.fukurou.engine.paper.PaperApi
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class FukurouCliTest {
    private lateinit var server: HttpServer

    @TempDir
    lateinit var dir: Path

    /** Mojang のマニフェストを返すか（false なら 500）。 */
    @Volatile
    private var manifestUp = true

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/manifest.json") { exchange ->
            val body = """
                {"latest":{"release":"26.3"},"versions":[
                  {"id":"26.4-snapshot-1","type":"snapshot","url":"x"},
                  {"id":"26.3","type":"release","url":"x"},
                  {"id":"26.3-rc-3","type":"snapshot","url":"x"},
                  {"id":"26.2","type":"release","url":"x"},
                  {"id":"26.1.2","type":"release","url":"x"},
                  {"id":"1.21.11","type":"release","url":"x"},
                  {"id":"1.20.1","type":"release","url":"x"},
                  {"id":"1.20","type":"release","url":"x"},
                  {"id":"1.19.4","type":"release","url":"x"}
                ]}
            """.trimIndent()
            respond(exchange, if (manifestUp) 200 else 500, if (manifestUp) body else "down")
        }
        server.createContext("/paper") { exchange ->
            val uri = exchange.requestURI.toString()
            val latest = Regex("""^/paper/versions/([^/]+)/builds/latest$""").find(uri)?.groupValues?.get(1)
            val list = Regex("""^/paper/versions/([^/]+)/builds\?""").find(uri)?.groupValues?.get(1)
            when {
                uri == "/paper" -> respond(
                    exchange,
                    200,
                    """{"versions":{"26.3":["26.3","26.3-rc-3"],"26.2":["26.2"],"26.1":["26.1.2"],"1.21":["1.21.11"],"1.20":["1.20.1","1.20"],"1.19":["1.19.4"]}}""",
                )
                latest != null -> respond(exchange, 200, """{"id":1,"channel":"${CHANNELS[latest] ?: "STABLE"}"}""")
                // 一覧は最新が許されないときだけ取りに来る。古い許されるビルドは無いものとする
                list != null -> respond(exchange, 200, "[]")
                else -> respond(exchange, 404, "{}")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** 実行結果。 */
    private data class Run(val code: Int, val out: String, val err: String)

    private suspend fun cli(vararg args: String): Run {
        val base = "http://127.0.0.1:${server.address.port}"
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = FukurouCli.run(
            args.toList(),
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
            MojangApi(manifestUrl = "$base/manifest.json"),
            PaperApi(projectUrl = "$base/paper"),
        )
        return Run(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    @Test
    @DisplayName("versions prints a compact JSON array oldest first")
    fun printsJson() = runTest {
        val run = cli("versions", "1.21.11-")
        assertEquals(0, run.code, run.err)
        assertEquals("[\"1.21.11\",\"26.1.2\",\"26.2\"]\n", run.out)
        assertEquals("[\"26.3\"]", cli("versions", "latest", "--paper-channel", "alpha").out.trim())
        assertEquals("[\"1.20\",\"1.20.1\",\"1.21.11\",\"26.1.2\",\"26.2\"]", cli("versions", "1.20-", "--paper-channel=beta").out.trim())
    }

    @Test
    @DisplayName("versions writes the array to --output")
    fun writesOutput() = runTest {
        val file = dir.resolve("nested/m.json")
        val run = cli("versions", "1.20-26.1.2", "--output", file.toString(), "--max-versions", "3")
        assertEquals(0, run.code, run.err)
        assertEquals("", run.out)
        assertEquals("[\"1.20.1\",\"1.21.11\",\"26.1.2\"]\n", Files.readString(file))
    }

    @Test
    @DisplayName("Invalid input exits with 2 and a fukurou: message on stderr")
    fun invalidInput() = runTest {
        val tooMany = cli("versions", "1.20-", "--max-versions", "2")
        assertEquals(2, tooMany.code)
        assertEquals(
            "fukurou: invalid version spec: 1.20- resolves to 4 versions, more than the maximum of 2; narrow the range or raise --max-versions\n",
            tooMany.err,
        )
        assertEquals("", tooMany.out)
        val alpha = cli("versions", "26.3")
        assertEquals(2, alpha.code)
        assertTrue(alpha.err.startsWith("fukurou: invalid version spec: Paper has no STABLE build for 26.3 (--paper-channel stable)"), alpha.err)
        assertEquals("fukurou: --max-versions must be at least 1\n", cli("versions", "latest", "--max-versions", "0").err)
        assertEquals(2, cli("versions", "latest", "--max-versions", "0").code)
        val channel = cli("versions", "latest", "--paper-channel", "rc")
        assertEquals(2, channel.code)
        assertEquals("fukurou: invalid version spec: unknown Paper channel 'rc'; expected one of stable, beta, alpha\n", channel.err)
        assertEquals(2, cli("versions", "1.19.4").code)
        assertEquals(2, cli("versions").code)
        assertEquals(2, cli("versions", "latest", "--bogus", "1").code)
        assertEquals(2, cli("frobnicate").code)
        assertEquals(2, cli().code)
    }

    @Test
    @DisplayName("Network failures exit with 1")
    fun networkFailure() = runTest {
        manifestUp = false
        val run = cli("versions", "latest")
        assertEquals(1, run.code)
        assertTrue(run.err.startsWith("fukurou: could not resolve versions: GET "), run.err)
    }

    private companion object {
        /** バージョンごとの最新ビルドのチャンネル（無ければ STABLE）。 */
        val CHANNELS = mapOf("26.3" to "ALPHA", "1.20" to "BETA")
    }
}
