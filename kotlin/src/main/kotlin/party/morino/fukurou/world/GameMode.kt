package party.morino.fukurou.world

/**
 * ゲームモード。
 *
 * @property id gamemode コマンドに渡す名前
 */
public enum class GameMode(public val id: String) {
    /** サバイバル。 */
    SURVIVAL("survival"),

    /** クリエイティブ。 */
    CREATIVE("creative"),

    /** アドベンチャー。 */
    ADVENTURE("adventure"),

    /** スペクテイター。 */
    SPECTATOR("spectator"),
}
