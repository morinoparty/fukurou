package party.morino.fukurou.spi.capability

import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.nio.file.Path
import kotlin.time.Duration

/** プラグインの導入と有効化の確認（純粋ではない唯一の能力）。 */
@FukurouSpi
public interface PluginSupport : Capability {
    /** プラグインを provision 済みのディレクトリへ入れる（Paper: plugins/ にコピー。同名は SetupException）。 */
    public fun install(serverDir: Path, plugins: List<ResolvedPlugin>)

    /** 有効化の確認（Paper: plugin_checks.py:82 check_plugins の移植、15 s）。戻り値は file 名 → enabled。 */
    public suspend fun checkEnabled(
        readLog: () -> String,
        plugins: List<ResolvedPlugin>,
        timeout: Duration,
    ): Map<String, Boolean>
}
