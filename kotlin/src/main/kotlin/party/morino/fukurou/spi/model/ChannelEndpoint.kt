package party.morino.fukurou.spi.model

/** コマンド経路の接続先。種類ごとに中身が違う。 */
public sealed interface ChannelEndpoint {
    /**
     * RCON。
     *
     * @property port 127.0.0.1 のポート
     * @property password rcon.password
     */
    public data class Rcon(val port: Int, val password: String) : ChannelEndpoint {
        /** パスワードをログに出さない。 */
        override fun toString(): String = "Rcon(port=$port, password=***)"
    }
}
