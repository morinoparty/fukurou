package party.morino.fukurou.engine.plugin

import party.morino.fukurou.engine.process.ExternalCommand
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * サーバーに必要な Java と、使う Java の版（plugins.py:115 required_java、java.py:28-50）。
 *
 * 判定そのもの（足りなければ SetupException）はどの種類にも共通なので ServerInstance.start が行う。
 */
internal object JavaRequirement {
    /** java -XshowSettings:properties の出力にある仕様バージョン（例: "25"、Java 8 は "1.8"）。 */
    private val SPEC_VERSION = Regex("""java\.specification\.version\s*=\s*(\d+)(?:\.(\d+))?""")

    /** プラグインのクラスファイルが求める Java の major 番号。クラスが無ければ null。 */
    fun pluginJava(plugin: ResolvedPlugin): Int? = plugin.classFileMajor?.let { it - PluginJarInspector.CLASS_MAJOR_OFFSET }

    /** Mojang の要求とプラグインのクラスファイルの版の大きい方。 */
    fun required(minecraftJava: Int, plugins: List<ResolvedPlugin>): Int =
        (listOf(minecraftJava) + plugins.mapNotNull(::pluginJava)).max()

    /** -XshowSettings:properties の出力から major 番号を取り出す。1.8 は 8 として扱う。 */
    fun parseMajor(output: String): Int? {
        val match = SPEC_VERSION.find(output) ?: return null
        val major = match.groupValues[1]
        val minor = match.groupValues[2]
        return if (major == "1" && minor.isNotEmpty()) minor.toInt() else major.toInt()
    }

    /** java の major 番号。この JVM 自身なら Runtime.version()、それ以外は実行して調べる。判定できなければ null。 */
    suspend fun actualMajor(java: Path): Int? {
        // テストを動かしている JVM と同じ java なら実行するまでもない
        val current = ProcessHandle.current().info().command().orElse(null)?.let { Path.of(it) }
        if (current != null && runCatching { Files.isSameFile(current, java) }.getOrDefault(false)) {
            return Runtime.version().feature()
        }
        if (!Files.isRegularFile(java)) throw SetupException("Java executable $java does not exist")
        val result = try {
            ExternalCommand.run(listOf(java.toString(), "-XshowSettings:properties", "-version"), timeout = 60.seconds)
        } catch (error: party.morino.fukurou.error.InputException) {
            throw SetupException("could not run $java: ${error.message}", error)
        }
        return parseMajor(result.stdout + result.stderr)
    }
}
