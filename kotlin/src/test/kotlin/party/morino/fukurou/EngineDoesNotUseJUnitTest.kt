package party.morino.fukurou

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/** JUnit を使わない利用（§5.7）のため、エンジンと公開 API が org.junit を参照しないことを確かめる。 */
class EngineDoesNotUseJUnitTest {
    /** 調べるパッケージ（junit/ 以外）。 */
    private val packages = listOf("engine", "result", "server", "player", "log", "world", "spi", "plugin")

    @Test
    @DisplayName("Engine and API classes do not reference org.junit")
    fun noJUnitReferences() {
        // テストのクラスパス上の main のクラスディレクトリ（ビルドの配置に依存しないよう codeSource から取る）
        val classes = Path.of(Fukurou::class.java.protectionDomain.codeSource.location.toURI())
        val root = classes.resolve("party/morino/fukurou")
        assertTrue(root.isDirectory(), "compiled classes not found at $root")
        // 定数プールの UTF-8 はそのままバイト列に出るので、"org/junit/" を探せば参照が分かる
        val needle = "org/junit/".toByteArray()
        val offenders = packages.flatMap { name ->
            val dir = root.resolve(name)
            if (!dir.isDirectory()) return@flatMap emptyList()
            Files.walk(dir).use { stream ->
                stream.filter { it.extension == "class" }.toList()
            }.filter { contains(it.readBytes(), needle) }.map { it.relativeTo(classes).toString() }
        }
        assertTrue(offenders.isEmpty(), "classes referencing org.junit: $offenders")
    }

    /** haystack に needle が含まれるか。 */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
