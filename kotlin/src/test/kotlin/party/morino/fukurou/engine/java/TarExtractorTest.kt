package party.morino.fukurou.engine.java

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import party.morino.fukurou.error.SetupException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Test

class TarExtractorTest {
    @TempDir
    lateinit var dir: Path

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["gnu", "pax", "ustar"])
    @DisplayName("Extracts long names, symlinks and executable bits written by tar")
    fun extractsRealTar(format: String) {
        val archive = FakeJdk.archive(dir.resolve("src"), dir.resolve("jdk.tar.gz"), 17, format)
        val home = dir.resolve("out")
        Files.newInputStream(archive).use { TarExtractor.extractTarGz(it, home, stripComponents = 1) }
        val java = home.resolve("bin/java")
        assertTrue(Files.isExecutable(java))
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(java)))
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("release"))))
        // ustar では 100 文字を超える名前を prefix に分け、gnu は L、pax は x のヘッダーに入れる
        assertEquals("license", Files.readString(home.resolve(FakeJdk.LONG_PATH)))
        val link = home.resolve("legal/java.desktop/LICENSE")
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(Path.of("../java.base/LICENSE"), Files.readSymbolicLink(link))
        assertEquals("base license", Files.readString(link))
        assertFalse(Files.exists(home.resolve("jdk-17.0.99+1")))
    }

    @Test
    @DisplayName("Entries that escape the destination are rejected")
    fun rejectsEscapes() {
        val cases = listOf(
            listOf(TarEntry("top/../../evil", "x")),
            listOf(TarEntry("/etc/evil", "x")),
            listOf(TarEntry("top/link", type = '2', link = "/etc/passwd")),
            listOf(TarEntry("top/link", type = '2', link = "../../outside")),
        )
        for (entries in cases) {
            val out = dir.resolve("escape-${entries.hashCode()}")
            assertThrows<SetupException> { TarExtractor.extractTar(ByteArrayInputStream(TarEntry.tar(entries)), out, stripComponents = 1) }
            assertFalse(Files.exists(dir.resolve("evil")))
        }
    }

    @Test
    @DisplayName("Links that escape only once earlier links are followed are rejected")
    fun rejectsEscapesThroughLinks() {
        val cases = listOf(
            // d は展開先そのもの。字面では out/d/x だが、実際には展開先の 1 つ上の x を指す
            listOf(TarEntry("top/d", type = '2', link = "."), TarEntry("top/d/g", type = '2', link = "../x"), TarEntry("top/d/g/evil", "x")),
            // リンクを通ってから上がる ".." は、字面と実際の行き先が食い違う
            listOf(TarEntry("top/s", type = '2', link = "."), TarEntry("top/t", type = '2', link = "s/../x")),
            // 展開先の外を指すリンクの下には何も書かない
            listOf(TarEntry("top/up", type = '2', link = ".."), TarEntry("top/up/evil", "x")),
        )
        for ((index, entries) in cases.withIndex()) {
            val parent = dir.resolve("case-$index")
            val out = parent.resolve("out")
            assertThrows<SetupException> { TarExtractor.extractTar(ByteArrayInputStream(TarEntry.tar(entries)), out, stripComponents = 1) }
            assertFalse(Files.exists(parent.resolve("x")))
            assertFalse(Files.exists(parent.resolve("evil")))
        }
    }

    @Test
    @DisplayName("Files can be written through links that stay inside the destination")
    fun writesThroughInsideLinks() {
        val entries = listOf(
            TarEntry("top/sub/", type = '5'),
            TarEntry("top/a", type = '2', link = "sub"),
            TarEntry("top/a/file", "inside"),
            TarEntry("top/sub/deep/up", type = '2', link = "../../a/file"),
        )
        val out = dir.resolve("inside")
        TarExtractor.extractTar(ByteArrayInputStream(TarEntry.tar(entries)), out, stripComponents = 1)
        assertEquals("inside", Files.readString(out.resolve("sub/file")))
        assertEquals("inside", Files.readString(out.resolve("sub/deep/up")))
    }

    @Test
    @DisplayName("GNU long names, pax paths, hard links and modes from a hand-written tar")
    fun handWritten() {
        val longName = "top/" + "d".repeat(120) + "/file"
        val entries = listOf(
            TarEntry("top/", type = '5'),
            TarEntry("././@LongLink", longName + "\u0000", type = 'L'),
            TarEntry("ignored-short-name", "long", mode = "755"),
            TarEntry("PaxHeader", paxRecord("path", "top/pax/" + "p".repeat(150)), type = 'x'),
            TarEntry("ignored", "pax"),
            TarEntry("././@LongLink", longName + "\u0000", type = 'K'),
            TarEntry("top/hard", type = '1', link = "ignored-short-link"),
            TarEntry("top/global", paxRecord("comment", "x"), type = 'g'),
        )
        val out = dir.resolve("hand")
        val gz = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(TarEntry.tar(entries)) } }.toByteArray()
        TarExtractor.extractTarGz(ByteArrayInputStream(gz), out, stripComponents = 1)
        val file = out.resolve("d".repeat(120) + "/file")
        assertEquals("long", Files.readString(file))
        assertTrue(Files.isExecutable(file))
        assertEquals("pax", Files.readString(out.resolve("pax/" + "p".repeat(150))))
        assertEquals("long", Files.readString(out.resolve("hard")))
        assertFalse(Files.exists(out.resolve("ignored")))
        assertFalse(Files.exists(out.resolve("global")))
    }

    /** pax の 1 レコード（長さは自身と改行を含む）。 */
    private fun paxRecord(key: String, value: String): String {
        val body = " $key=$value\n"
        var length = body.length + 1
        while ((length.toString() + body).length != length) length = (length.toString() + body).length
        return length.toString() + body
    }
}

/**
 * テスト用の tar の 1 エントリ（ustar のヘッダーを最小限に書く）。
 *
 * @property name ヘッダーの name（100 バイトまで）
 * @property content 本体
 * @property type typeflag
 * @property link linkname
 * @property mode 8 進の mode
 */
internal data class TarEntry(
    val name: String,
    val content: String = "",
    val type: Char = '0',
    val link: String = "",
    val mode: String = "644",
) {
    companion object {
        /** エントリを並べた tar（末尾の 2 ブロックを含む）。 */
        fun tar(entries: List<TarEntry>): ByteArray {
            val out = ByteArrayOutputStream()
            for (entry in entries) {
                val data = entry.content.toByteArray()
                val header = ByteArray(512)
                fun put(offset: Int, text: String) = text.toByteArray().copyInto(header, offset)
                put(0, entry.name.take(100))
                put(100, entry.mode.padStart(7, '0'))
                put(108, "0000000")
                put(116, "0000000")
                put(124, data.size.toString(8).padStart(11, '0'))
                put(136, "00000000000")
                header[156] = entry.type.code.toByte()
                put(157, entry.link.take(100))
                put(257, "ustar")
                put(263, "00")
                // チェックサムは extractor が見ないが、形だけ整える
                put(148, "        ")
                put(148, (header.sumOf { it.toInt() and 0xff }).toString(8).padStart(6, '0') + "\u0000 ")
                out.write(header)
                out.write(data)
                out.write(ByteArray((512 - data.size % 512) % 512))
            }
            out.write(ByteArray(1024))
            return out.toByteArray()
        }
    }
}

/** テスト用の偽の JDK（bin/java は版を表示するだけのシェルスクリプト）。 */
internal object FakeJdk {
    /** 100 文字を超えるパス（ustar の name に収まらない）。 */
    val LONG_PATH: String = "legal/" + "jdk.internal.long.module.".repeat(2) + "x/" + "LICENSE-with-a-rather-long-file-name-for-tests.txt"

    /**
     * 偽の JDK のツリーを src に作り、tar（format は gnu / pax / ustar）で archive に固める。
     *
     * @param reportedMajor bin/java が java.specification.version として表示する major
     */
    fun archive(src: Path, archive: Path, reportedMajor: Int, format: String = "gnu"): Path {
        val top = src.resolve("jdk-17.0.99+1")
        val java = top.resolve("bin/java")
        Files.createDirectories(java.parent)
        Files.writeString(java, "#!/bin/sh\necho '    java.specification.version = $reportedMajor' >&2\necho 'openjdk version \"$reportedMajor\"' >&2\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.writeString(top.resolve("release"), "JAVA_VERSION=\"$reportedMajor\"\n")
        Files.setPosixFilePermissions(top.resolve("release"), PosixFilePermissions.fromString("rw-r--r--"))
        val long = top.resolve(LONG_PATH)
        Files.createDirectories(long.parent)
        Files.writeString(long, "license")
        Files.createDirectories(top.resolve("legal/java.base"))
        Files.writeString(top.resolve("legal/java.base/LICENSE"), "base license")
        Files.createDirectories(top.resolve("legal/java.desktop"))
        Files.createSymbolicLink(top.resolve("legal/java.desktop/LICENSE"), Path.of("../java.base/LICENSE"))
        val process = ProcessBuilder("tar", "--format=$format", "-czf", archive.toString(), "-C", src.toString(), "jdk-17.0.99+1")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "tar failed: $output" }
        return archive
    }
}
