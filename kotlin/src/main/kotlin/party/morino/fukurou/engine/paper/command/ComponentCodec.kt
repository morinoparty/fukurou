package party.morino.fukurou.engine.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.json.JSONOptions
import party.morino.fukurou.version.MinecraftVersion

/**
 * Component をコマンド（tellraw / title / bossbar）に埋め込む JSON にする。純粋。
 *
 * 1.21.5 でテキストコンポーネントの形が変わった（clickEvent → click_event など）ので、
 * サーバーのバージョンに合わせたデータバージョンで書き出す。
 * 1.21.5 以降のコマンドはコンポーネントを SNBT として読むが、この JSON はそのまま SNBT としても有効。
 *
 * @property version サーバーの Minecraft のバージョン
 */
internal class ComponentCodec(val version: MinecraftVersion) {
    /** 書き出しに使うデータバージョン。 */
    val dataVersion: Int = if (version >= SNAKE_CASE_SINCE) DATA_VERSION_1_21_5 else DATA_VERSION_1_21_4

    /** データバージョンに合わせた Gson の書き出し（Gson は既定で 1 行のコンパクトな JSON を出す）。 */
    private val serializer: GsonComponentSerializer = GsonComponentSerializer.builder()
        .options(JSONOptions.byDataVersion().at(dataVersion))
        .build()

    /** component を 1 行の JSON にする。 */
    fun encode(component: Component): String = serializer.serialize(component)

    /** JSON を Component に戻す（テストでの往復の確認用）。 */
    fun decode(json: String): Component = serializer.deserialize(json)

    private companion object {
        /** コンポーネントの形が snake_case に変わったバージョン。 */
        private val SNAKE_CASE_SINCE = MinecraftVersion("1.21.5")

        /** 1.21.5 のデータバージョン。 */
        private const val DATA_VERSION_1_21_5 = 4325

        /** 1.21.4 のデータバージョン。 */
        private const val DATA_VERSION_1_21_4 = 4189
    }
}
