package party.morino.fukurou.engine.java

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import party.morino.fukurou.engine.net.Sha256
import party.morino.fukurou.error.SetupException
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

// ExternalCommand の時間切れが仮想時間で先に切れないよう、runTest ではなく runBlocking を使う
class ServerJavaResolverTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

    @TempDir
    lateinit var dir: Path

    /** 配るアーカイブ。 */
    private lateinit var archive: ByteArray

    /** API が示す checksum（null ならアーカイブの本当の値）。 */
    @Volatile
    private var advertisedSha: String? = null

    @BeforeEach
    fun start() {
        archive = Files.readAllBytes(FakeJdk.archive(dir.resolve("src"), dir.resolve("fake.tar.gz"), 17))
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Adoptium の /v3/assets/latest/<major>/hotspot を真似る
        server.createContext("/v3/assets/latest/") { exchange ->
            requests += exchange.requestURI.toString()
            val body = """
                [{"binary":{"architecture":"x64","image_type":"jdk","os":"linux",
                  "package":{"checksum":"${advertisedSha ?: Sha256.of(archive)}","link":"${url("/download/OpenJDK17U-jdk_x64_linux_hotspot_17.0.99_1.tar.gz")}",
                             "name":"OpenJDK17U-jdk_x64_linux_hotspot_17.0.99_1.tar.gz","size":${archive.size}}},
                  "release_name":"jdk-17.0.99+1","vendor":"eclipse"}]
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/download/") { exchange ->
            requests += exchange.requestURI.toString()
            exchange.sendResponseHeaders(200, archive.size.toLong())
            exchange.responseBody.use { it.write(archive) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun jdks() = TemurinJdks(apiUrl = url(""), architectureOverride = "x64")

    private suspend fun auto(required: Int, env: Map<String, String> = emptyMap(), currentFeature: Int = 21): Path =
        ServerJavaResolver.auto(
            required = required,
            currentFeature = currentFeature,
            currentJava = Path.of("/current/bin/java"),
            env = env,
            cacheDir = dir.resolve("cache"),
            jdks = jdks(),
            log = { logs += it },
            osArch = "amd64",
        )

    @Test
    @DisplayName("A bare serverJava name is looked up on PATH; paths are kept as they are")
    fun bareNameOnPath() {
        val first = Files.createDirectories(dir.resolve("first"))
        val second = Files.createDirectories(dir.resolve("second"))
        // 実行できないファイルは飛ばす（シェルと同じ）
        Files.writeString(first.resolve("java"), "not executable")
        val onPath = second.resolve("java")
        Files.writeString(onPath, "#!/bin/sh\n")
        onPath.toFile().setExecutable(true)
        val path = listOf(dir.resolve("missing"), first, second).joinToString(File.pathSeparator)
        assertEquals(onPath.toAbsolutePath(), ServerJavaResolver.executable(Path.of("java"), path))
        val absolute = Path.of("/opt/jdk/bin/java")
        assertEquals(absolute, ServerJavaResolver.executable(absolute, ""))
        val error = assertThrows<SetupException> { ServerJavaResolver.executable(Path.of("java"), first.toString()) }
        assertTrue("not found on PATH" in error.message.orEmpty(), error.message)
    }

    @Test
    @DisplayName("The test JVM's java is an existing file")
    fun currentJava() {
        assertTrue(Files.isRegularFile(ServerJavaResolver.currentJava()))
    }

    @Test
    @DisplayName("Uses the test JVM when its major matches")
    fun usesCurrent() = runBlocking {
        assertEquals(Path.of("/current/bin/java"), auto(21, currentFeature = 21))
        assertTrue(requests.isEmpty())
        assertTrue(logs.single().contains("test JVM"))
    }

    @Test
    @DisplayName("Uses JAVA_HOME_<major>_X64 from GitHub runners when it has bin/java")
    fun usesRunnerJdk() = runBlocking {
        val home = dir.resolve("hosted/17")
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/java"), "")
        assertEquals(home.resolve("bin/java"), auto(17, mapOf("JAVA_HOME_17_X64" to home.toString())))
        assertTrue(requests.isEmpty())
    }

    @Test
    @DisplayName("Downloads, verifies and caches Temurin when nothing matches")
    fun downloadsTemurin() = runBlocking {
        // JAVA_HOME_17_X64 に java が無ければ使わない
        val java = auto(17, mapOf("JAVA_HOME_17_X64" to dir.resolve("missing").toString()))
        assertEquals(dir.resolve("cache/jdks/temurin-17/bin/java"), java)
        assertTrue(Files.isExecutable(java))
        assertTrue(requests[0].startsWith("/v3/assets/latest/17/hotspot?"), requests[0])
        assertTrue(requests[0].contains("architecture=x64") && requests[0].contains("os=linux") && requests[0].contains("image_type=jdk"))
        assertEquals(2, requests.size)
        // 展開したアーカイブと一時ディレクトリは残さない
        assertFalse(Files.exists(dir.resolve("cache/jdks/OpenJDK17U-jdk_x64_linux_hotspot_17.0.99_1.tar.gz")))
        assertFalse(Files.exists(dir.resolve("cache/jdks/temurin-17.part")))
        assertTrue(logs.any { it.contains("jdk-17.0.99+1") })
        // 2 回目はキャッシュを使い、問い合わせない
        assertEquals(java, auto(17))
        assertEquals(2, requests.size)
    }

    @Test
    @DisplayName("A checksum mismatch installs nothing")
    fun checksumMismatch() = runBlocking {
        advertisedSha = "0".repeat(64)
        val error = assertThrows<SetupException> { runBlocking { auto(17) } }
        assertTrue(error.message!!.contains("checksum mismatch"), error.message)
        assertFalse(Files.exists(dir.resolve("cache/jdks/temurin-17")))
    }

    @Test
    @DisplayName("A corrupt archive is a setup error and leaves no partial JDK")
    fun corruptArchive() = runBlocking {
        // checksum は一致するが gzip ではない（壊れたミラーなど）
        archive = "not a tar.gz".toByteArray()
        val error = assertThrows<SetupException> { runBlocking { auto(17) } }
        assertTrue(error.message!!.contains("could not extract"), error.message)
        assertFalse(Files.exists(dir.resolve("cache/jdks/temurin-17")))
        assertFalse(Files.exists(dir.resolve("cache/jdks/temurin-17.part")))
    }

    @Test
    @DisplayName("A JDK that reports another major is rejected")
    fun wrongMajor() = runBlocking {
        archive = Files.readAllBytes(FakeJdk.archive(dir.resolve("src11"), dir.resolve("fake11.tar.gz"), 11))
        val error = assertThrows<SetupException> { runBlocking { auto(17) } }
        assertTrue(error.message!!.contains("reports Java 11, not 17"), error.message)
    }

    @Test
    @DisplayName("Unsupported architectures ask for current or a path")
    fun unsupportedArchitecture() {
        assertEquals("x64", TemurinJdks.adoptiumArchitecture("amd64"))
        assertEquals("aarch64", TemurinJdks.adoptiumArchitecture("aarch64"))
        val error = assertThrows<SetupException> { TemurinJdks.adoptiumArchitecture("ppc64le") }
        assertTrue(error.message!!.contains("fukurou.serverJava=current"))
    }
}
