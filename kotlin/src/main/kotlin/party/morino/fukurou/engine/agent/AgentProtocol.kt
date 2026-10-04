package party.morino.fukurou.engine.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/** エージェントのプロトコル（v3 設計 §1.2）の定数。 */
internal object AgentProtocol {
    /** プロトコルの版。 */
    const val VERSION: Int = 1

    /** 1 行（1 メッセージ）の上限（16 MiB）。 */
    const val MAX_LINE_BYTES: Int = 16 * 1024 * 1024

    /** エージェントの待ち受けアドレス。 */
    const val HOST: String = "127.0.0.1"

    /** 行の JSON。 */
    val JSON: Json = Json { ignoreUnknownKeys = true }
}

/**
 * エージェントが返した error（ok: false）。呼び出し側（AgentAccess）が公開の例外に読み替える。
 *
 * @property type error.type（auth / bad_request / not_found / task_failed / timeout / internal）
 * @property remoteMessage error.message
 * @property exception サーバーで投げられた例外のクラス名
 * @property stackTrace サーバーでのスタックトレース
 */
internal class AgentErrorException(
    val type: String,
    val remoteMessage: String,
    val exception: String?,
    val stackTrace: String?,
) : RuntimeException("$type: $remoteMessage") {
    companion object {
        /** error のオブジェクトから作る。 */
        fun of(error: JsonElement?): AgentErrorException {
            val obj = error as? JsonObject
            fun text(key: String): String? = (obj?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
            return AgentErrorException(
                type = text("type") ?: "internal",
                remoteMessage = text("message") ?: "the agent returned an error without a message",
                exception = text("exception"),
                stackTrace = text("stackTrace"),
            )
        }
    }
}

/** エージェントとの接続が切れた（読み書きの失敗・EOF・こちらから閉じた）。 */
internal class AgentConnectionLostException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** エージェントがプロトコルに合わない応答をした（hello の形・版の違い・大きすぎる行など）。 */
internal class AgentProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)
