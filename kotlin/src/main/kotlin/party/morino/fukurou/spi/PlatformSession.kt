package party.morino.fukurou.spi

/** 起動中のセッション（エンジンが実装）。能力が必要に応じて参照する。 */
@FukurouSpi
public interface PlatformSession {
    /** このセッションのコマンド経路。 */
    public val channel: CommandChannel?

    /** 参加済みのプレイヤー名（参加順）。 */
    public val joinedPlayers: List<String>

    /** セッションの開始からのサーバーログ。 */
    public fun readServerLog(): String
}
