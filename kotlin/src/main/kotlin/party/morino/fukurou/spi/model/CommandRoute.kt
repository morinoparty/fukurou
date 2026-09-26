package party.morino.fukurou.spi.model

/** 計画されたコマンドをどのサーバーで実行するか。 */
public sealed interface CommandRoute {
    /** このサーバーのコマンド経路。 */
    public data object Self : CommandRoute

    /**
     * プロキシの後ろで、プレイヤーがいるバックエンド（§10）。Paper は出さない。
     *
     * @property player 対象のプレイヤー
     */
    public data class PlayerBackend(val player: String) : CommandRoute
}
