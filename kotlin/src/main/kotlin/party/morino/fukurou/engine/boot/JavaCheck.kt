package party.morino.fukurou.engine.boot

import party.morino.fukurou.engine.net.MojangApi
import party.morino.fukurou.engine.plugin.JavaRequirement
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.plugin.PluginSetBuilder
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import party.morino.fukurou.version.MinecraftVersion
import java.nio.file.Path

/**
 * サーバーの Java が Minecraft とプラグインの要求を満たすかを、起動前に確かめる（suite_run.py:499-513 _check_java）。
 *
 * 種類に共通の検査なので、ServerPlatform ではなくエンジンが行う（§4.3）。
 */
internal object JavaCheck {
    /**
     * 要求を満たさなければ SetupException。Java の版が分からなければ警告だけ出す。
     *
     * @param mojang Mojang のバージョン情報（その版の公式サーバーが要求する Java）
     * @param version 起動する Minecraft の版
     * @param java サーバーを起動する java
     * @param plugins 解決済みのプラグイン
     * @param warn harness.log への警告
     * @return サーバーの Java の major 番号（分からなければ null）
     */
    suspend fun check(
        mojang: MojangApi,
        version: MinecraftVersion,
        java: Path,
        plugins: List<ResolvedPlugin>,
        warn: (String) -> Unit,
    ): Int? {
        val minecraftJava = mojang.javaMajor(version.id)
        val required = JavaRequirement.required(minecraftJava, plugins)
        val actual = JavaRequirement.actualMajor(java)
        if (actual == null) {
            // 版が分からないだけで止めると、動く環境まで止めてしまう
            warn("could not determine the Java version of $java; Java $required or later is needed")
            return null
        }
        if (actual >= required) return actual
        var message = "Minecraft ${version.id} with these plugins needs Java $required or later, but $java is Java $actual"
        // テスト対象だけなら足りるなら、依存プラグインが新しい Java を要求している（setup の java-version は依存を見ない）
        val underTest = plugins.filter { it.role == PluginSetBuilder.UNDER_TEST }
        if (JavaRequirement.required(minecraftJava, underTest) < required) {
            message += "; a dependency needs the newer Java, so set the Java version explicitly (-Pfukurou.serverJava or java-version in the action)"
        }
        throw SetupException(message)
    }
}
