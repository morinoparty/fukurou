package party.morino.fukurou.engine.client

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class TarGzReaderTest {
    /** ustar のヘッダー 1 つと本体（512 バイト境界まで 0 で埋める）。 */
    private fun entry(name: String, type: Char, data: ByteArray = ByteArray(0), prefix: String = ""): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        String.format("%011o", data.size).toByteArray().copyInto(header, 124)
        header[156] = type.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        prefix.toByteArray().copyInto(header, 345)
        val padded = ByteArray((data.size + 511) / 512 * 512)
        data.copyInto(padded)
        return header + padded
    }

    private fun gzip(vararg entries: ByteArray): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> entries.forEach(gz::write); gz.write(ByteArray(1024)) }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    @DisplayName("A nested portablemc regular file is extracted and other entries are skipped")
    fun nestedEntry() {
        val binary = ByteArray(1500) { (it % 251).toByte() }
        val archive = gzip(
            entry("portablemc-5.0.4/", '5'),
            // 名前が同じでもディレクトリやリンクは対象外
            entry("portablemc", '2'),
            entry("README.md", '0', "hello".toByteArray()),
            entry("portablemc", '0', binary, prefix = "portablemc-5.0.4-linux-x86_64-gnu/bin"),
        )
        assertArrayEquals(binary, TarGzReader.extract(archive, "portablemc"))
    }

    @Test
    @DisplayName("A missing entry returns null")
    fun missingEntry() {
        assertNull(TarGzReader.extract(gzip(entry("LICENSE", '0', "MIT".toByteArray())), "portablemc"))
    }
}
