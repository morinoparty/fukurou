package party.morino.fukurou.error

/**
 * プレイヤーのクライアントが終了した。テストは error（phase client）になる。
 *
 * @property player 終了したクライアントのプレイヤー名
 */
public class ClientDiedException(public val player: String, message: String) : FukurouException(message)
