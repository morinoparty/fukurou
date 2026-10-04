package party.morino.fukurou.error

/**
 * エージェントで実行したタスク（execute）がサーバーの上で例外を投げた。テストの failed として記録する。
 *
 * @property task タスク名
 * @property remoteType サーバーで投げられた例外のクラス名
 * @property remoteStackTrace サーバーでのスタックトレース
 */
public class ServerTaskException(
    public val task: String,
    public val remoteType: String?,
    message: String,
    public val remoteStackTrace: String?,
) : RuntimeException("task '$task' failed on the server: ${remoteType ?: "error"}: $message")

/**
 * エージェントへの要求が受け付けられなかった（プレイヤーやワールドが無い、引数の誤り、未知のイベントの種類など）。
 * テストの failed として記録する。
 *
 * @property type エージェントの error.type（not_found / bad_request など）
 */
public class AgentRequestException(public val type: String, message: String) : RuntimeException(message)
