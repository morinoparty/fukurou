package party.morino.fukurou.spi.model

/**
 * サーバー内エージェントの接続先（127.0.0.1:<port>、v3 設計 §1.1）。
 *
 * @property port エージェントが待ち受けるポート
 * @property token hello で送るトークン
 */
public data class AgentEndpoint(val port: Int, val token: String) {
    /** トークンをログに出さない。 */
    override fun toString(): String = "AgentEndpoint(port=$port, token=***)"
}
