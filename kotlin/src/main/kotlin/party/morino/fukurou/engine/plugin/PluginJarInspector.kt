package party.morino.fukurou.engine.plugin

import party.morino.fukurou.engine.net.Sha256
import party.morino.fukurou.error.SetupException
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * jar を開いて記述ファイルとクラスファイルの版を読み取る（plugins.py:87-113 PluginJar.inspect）。
 */
internal object PluginJarInspector {
    /** Paper プラグインの記述ファイルを優先し、無ければ Bukkit 形式を読む。 */
    private val DESCRIPTOR_FILES = listOf("paper-plugin.yml", "plugin.yml")

    /** クラスファイルのマジックナンバー。 */
    private val CLASS_FILE_MAGIC = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

    /** マルチリリース jar の版別クラスは実行環境に合わせて選ばれるため、必要な Java の判定に含めない。 */
    private const val MULTI_RELEASE_PREFIX = "META-INF/versions/"

    /** クラスファイルの major 番号から Java の版を求める差分（Java 8 = 52）。 */
    const val CLASS_MAJOR_OFFSET: Int = 44

    /**
     * 読み取った結果。
     *
     * @property sha256 jar の sha256
     * @property name 記述ファイルの name
     * @property version 記述ファイルの version（文字列のまま）
     * @property prefix 記述ファイルの prefix（サーバーログの [..] がこの値になる）
     * @property classFileMajor クラスファイルの major の最大値。クラスが無ければ null
     */
    data class Inspection(
        val sha256: String,
        val name: String?,
        val version: String?,
        val prefix: String?,
        val classFileMajor: Int?,
    )

    /** path の jar を読む。jar として読めない、または記述ファイルが壊れていれば SetupException。 */
    fun inspect(path: Path): Inspection {
        val (descriptor, major) = try {
            ZipFile(path.toFile()).use { jar -> readDescriptor(jar, path) to maxClassMajor(jar) }
        } catch (error: ZipException) {
            throw SetupException("$path is not a readable jar file: ${error.message}", error)
        } catch (error: IOException) {
            throw SetupException("$path is not a readable jar file: ${error.message}", error)
        }
        return Inspection(
            sha256 = Sha256.of(path),
            name = descriptor["name"],
            version = descriptor["version"],
            prefix = descriptor["prefix"],
            classFileMajor = major,
        )
    }

    /** paper-plugin.yml または plugin.yml を読む。どちらも無ければ空。 */
    private fun readDescriptor(jar: ZipFile, path: Path): Map<String, String> {
        val entry = DESCRIPTOR_FILES.firstNotNullOfOrNull { jar.getEntry(it) } ?: return emptyMap()
        val text = jar.getInputStream(entry).use { it.readAllBytes().toString(Charsets.UTF_8) }
        return try {
            PluginDescriptorParser.parse(text)
        } catch (error: IllegalArgumentException) {
            throw SetupException("$path: invalid plugin descriptor: ${error.message}", error)
        }
    }

    /** jar 内のクラスファイルの major 番号（バイト 6〜7）の最大値。クラスが無ければ null。 */
    private fun maxClassMajor(jar: ZipFile): Int? =
        jar.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith(MULTI_RELEASE_PREFIX) }
            .mapNotNull { entry ->
                // 先頭の 8 バイト（マジック・minor・major）だけ読む
                val header = jar.getInputStream(entry).use { it.readNBytes(8) }
                if (header.size == 8 && header.copyOfRange(0, 4).contentEquals(CLASS_FILE_MAGIC)) {
                    ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                } else {
                    null
                }
            }
            .maxOrNull()
}
