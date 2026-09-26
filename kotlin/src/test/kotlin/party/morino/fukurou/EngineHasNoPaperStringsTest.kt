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

/** Minecraft のコマンドの文字列は engine/paper にだけ置く（§2、D3）。他の種類を足してもエンジンを書き換えずに済むように。 */
class EngineHasNoPaperStringsTest {
    /** エンジンに現れてはいけない Paper / バニラの文字列。 */
    private val needles = listOf("tellraw", "tp ", "gamemode ", "joined the game")

    @Test
    @DisplayName("Engine classes outside engine/paper contain no Paper command strings")
    fun noPaperStrings() {
        // テストのクラスパス上の main のクラスディレクトリ（ビルドの配置に依存しないよう codeSource から取る）
        val classes = Path.of(Fukurou::class.java.protectionDomain.codeSource.location.toURI())
        val engine = classes.resolve("party/morino/fukurou/engine")
        assertTrue(engine.isDirectory(), "compiled engine classes not found at $engine")
        val paper = engine.resolve("paper")
        val files = Files.walk(engine).use { stream -> stream.filter { it.extension == "class" && !it.startsWith(paper) }.toList() }
        // 定数プールの文字列は修正 UTF-8 でそのままバイト列に出る（ASCII の範囲では UTF-8 と同じ）
        val offenders = files.flatMap { file ->
            val bytes = file.readBytes()
            needles.filter { contains(bytes, it.toByteArray()) }.map { "${file.relativeTo(classes)}: \"$it\"" }
        }
        assertTrue(files.isNotEmpty(), "no engine classes were scanned")
        assertTrue(offenders.isEmpty(), "Paper command strings outside engine/paper: $offenders")
    }

    /** haystack に needle が含まれるか。 */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
