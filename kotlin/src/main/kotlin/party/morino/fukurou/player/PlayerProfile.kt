package party.morino.fukurou.player

import java.util.Locale

/**
 * 参加前のプレイヤー。操作は持たず、GameServer.join で Player になる。
 *
 * 名前は ^[A-Za-z0-9_]{3,16}$ で、"server" は予約。
 *
 * @property name プレイヤー名（オフラインモードのユーザー名）
 * @property op リセットのたびに op にするか
 * @property locale クライアントの言語。options.txt の lang（ja_JP → ja_jp）と Adventure の Identity.LOCALE に使う。
 *   言語と国の両方が要る（Locale.JAPAN、Locale.US、Locale.forLanguageTag("ja-JP") など）
 */
public data class PlayerProfile(val name: String, val op: Boolean = false, val locale: Locale = Locale.US) {
    init {
        // Minecraft のユーザー名の規則。ファイル名やコマンドにそのまま使うので記号は入れさせない
        require(NAME.matches(name)) { "player name '$name' must match ${NAME.pattern}" }
        // on: "server" と区別できなくなるため予約語にしている
        require(name != SERVER_TARGET) { "'$SERVER_TARGET' is reserved and cannot be a player name" }
        // Minecraft の言語コードは <言語>_<国>。国の無い Locale（Locale.JAPANESE など）はコードにできない
        require(LANGUAGE.matches(minecraftLanguage)) {
            "locale '$locale' must have a language and a country (for example Locale.JAPAN or Locale.forLanguageTag(\"ja-JP\"))"
        }
    }

    /** Minecraft の言語コード（options.txt の lang、サーバーの Player#getLocale と同じ形）。Locale.JAPAN → "ja_jp"。 */
    public val minecraftLanguage: String
        get() = "${locale.language}_${locale.country}".lowercase(Locale.ROOT)

    private companion object {
        /** プレイヤー名の規則。 */
        private val NAME = Regex("^[A-Za-z0-9_]{3,16}$")

        /** ステップの on でサーバーを表す予約語。 */
        private const val SERVER_TARGET = "server"

        /** Minecraft の言語コードの形（en_us、ja_jp、fil_ph など）。 */
        private val LANGUAGE = Regex("^[a-z]{2,3}_[a-z0-9]{2,3}$")
    }
}
