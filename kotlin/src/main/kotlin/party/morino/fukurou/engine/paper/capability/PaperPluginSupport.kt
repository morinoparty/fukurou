package party.morino.fukurou.engine.paper.capability

import kotlinx.coroutines.delay
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.spi.capability.PluginSupport
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Paper のプラグインの導入（server_dir.py:53 copy_plugins）と有効化の確認（plugin_checks.py:82 check_plugins）。
 *
 * @property warn 確認に失敗したときの説明の書き出し先（harness.log）
 */
internal class PaperPluginSupport(private val warn: (String) -> Unit = {}) : PluginSupport {
    override fun install(serverDir: Path, plugins: List<ResolvedPlugin>) {
        val pluginsDir = serverDir.resolve(PLUGINS_DIR)
        Files.createDirectories(pluginsDir)
        val seen = mutableMapOf<String, Path>()
        for (plugin in plugins) {
            // 同じファイル名は上書きになり、片方が黙って消えるため誤りとして扱う
            seen[plugin.file]?.let { first ->
                throw SetupException("two plugin jars have the same file name ${plugin.file}: $first and ${plugin.path}")
            }
            seen[plugin.file] = plugin.path
            Files.copy(plugin.path, pluginsDir.resolve(plugin.file), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 全プラグインの有効化を確認し、ファイル名 → 有効化できたか を返す。
     *
     * Python 版と違って例外は投げない（SPI の契約）。false を run の失敗にするのはエンジンで、
     * その説明（Python の PluginCheckError の文面）は harness.log に書く。
     * 起動完了（Done）の時点で通常は有効化が終わっているため、timeout は短くてよい。
     */
    override suspend fun checkEnabled(
        readLog: () -> String,
        plugins: List<ResolvedPlugin>,
        timeout: Duration,
    ): Map<String, Boolean> {
        val identities = plugins.map(PluginIdentity::of)
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var state = PluginLogState.read(readLog(), identities)
        // エラーが出るか全部が有効になるまで、0.5 秒ごとに読み直す
        while (!state.done() && deadline.hasNotPassedNow()) {
            delay(POLL)
            state = PluginLogState.read(readLog(), identities)
        }
        state.failureMessage()?.let(warn)
        return state.enabledByFile()
    }

    private companion object {
        /** プラグインを置くディレクトリ。 */
        private const val PLUGINS_DIR = "plugins"

        /** ログを読み直す間隔（plugin_checks.py と同じ 0.5 秒）。 */
        private val POLL = 500.milliseconds
    }
}
