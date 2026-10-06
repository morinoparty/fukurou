package party.morino.fukurou.server.paper

import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.engine.paper.PaperPlatform
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.server.ServerType
import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.version.MinecraftVersion

/**
 * Paper サーバー。properties は DEFAULT_PROPERTIES の上に重ねる（fukurou が管理するキーは上書きできない）。
 *
 * @property version Minecraft のバージョン
 * @property channel 受け入れる最も不安定なチャンネル
 * @property build 固定するビルド。チャンネルがしきい値より不安定でも警告して使う
 * @property properties server.properties の上書き
 * @property agent サーバー内エージェント（FukurouAgent）を入れるか。false なら状態の取得・イベント・execute は使えない
 */
public data class Paper(
    val version: MinecraftVersion,
    val channel: PaperChannel = PaperChannel.Stable,
    val build: Int? = null,
    val properties: Map<String, String> = emptyMap(),
    val agent: Boolean = true,
) : ServerType {
    override val id: String get() = "paper"

    override val minecraftVersion: MinecraftVersion get() = version

    /** server.properties の上書きを足した値。 */
    public fun withProperties(vararg pairs: Pair<String, String>): Paper = copy(properties = properties + pairs)

    @FukurouSpi
    override fun createPlatform(services: PlatformServices): ServerPlatform = PaperPlatform(this, services)

    public companion object {
        /**
         * バージョンを文字列で指定する（Paper("1.21.11", agent = false)）。ほかの引数は主コンストラクタと同じ。
         *
         * MinecraftVersion は JVM では String なので、同じ引数のコンストラクタは主コンストラクタとシグネチャが衝突する。
         * そのため invoke で用意する。
         */
        public operator fun invoke(
            version: String,
            channel: PaperChannel = PaperChannel.Stable,
            build: Int? = null,
            properties: Map<String, String> = emptyMap(),
            agent: Boolean = true,
        ): Paper = Paper(MinecraftVersion(version), channel, build, properties, agent)

        /**
         * fukurou.minecraftVersion / fukurou.paperChannel / fukurou.paperBuild を読む。
         * version が無く defaultVersion も null なら SetupException。
         * channel の優先順位: プロパティ > defaultChannel。
         */
        public fun fromProperties(
            config: FukurouConfig,
            defaultVersion: String? = null,
            defaultChannel: PaperChannel = PaperChannel.Stable,
        ): Paper {
            // 空文字は未設定と同じに扱う（CI の matrix が空で渡すことがある）
            fun prop(key: String): String? = config.properties[key]?.trim()?.takeIf { it.isNotEmpty() }
            val versionText = prop("fukurou.minecraftVersion") ?: defaultVersion
                ?: throw SetupException("Set -Pfukurou.minecraftVersion=26.3 or pass defaultVersion")
            val version = try {
                MinecraftVersion(versionText)
            } catch (error: IllegalArgumentException) {
                throw SetupException("fukurou.minecraftVersion: ${error.message}", error)
            }
            val channel = prop("fukurou.paperChannel")?.let { text ->
                try {
                    PaperChannel.parse(text)
                } catch (error: IllegalArgumentException) {
                    throw SetupException("fukurou.paperChannel: ${error.message}", error)
                }
            } ?: defaultChannel
            val build = prop("fukurou.paperBuild")?.let { text ->
                text.toIntOrNull() ?: throw SetupException("fukurou.paperBuild must be a build number, got '$text'")
            }
            return Paper(version, channel, build)
        }
    }
}
