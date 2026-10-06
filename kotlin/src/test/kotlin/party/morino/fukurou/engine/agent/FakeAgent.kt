package party.morino.fukurou.engine.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * テスト用のエージェント（v3 設計 §1.2 のプロトコルを話す ServerSocket）。Minecraft は使わない。
 *
 * hello はトークンを確かめて答え、以後のリクエストは [handler] に渡す。handler は [Connection] を通して応答やイベントを送る。
 *
 * @property token 受け付けるトークン
 * @property handler hello 以外のリクエストの扱い
 */
class FakeAgent(
    val token: String = TOKEN,
    port: Int = 0,
    private val handler: Connection.(JsonObject) -> Unit = { reply(it) },
) : AutoCloseable {
    private val server = ServerSocket(port, 50, InetAddress.getLoopbackAddress())

    /** 待ち受けているポート。 */
    val port: Int get() = server.localPort

    /** 受け付けた接続（hello の通ったものも通らなかったものも）。 */
    val connections: MutableList<Connection> = CopyOnWriteArrayList()

    /** hello 以外に受け取ったリクエスト（届いた順）。 */
    val requests: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** hello を受け取った回数。 */
    @Volatile
    var hellos: Int = 0
        private set

    init {
        Thread.ofPlatform().daemon().name("fake-agent-accept").start {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val connection = Connection(socket)
                connections += connection
                Thread.ofPlatform().daemon().name("fake-agent-conn").start { connection.serve() }
            }
        }
    }

    /** 待ち受けをやめる（張られた接続はそのまま）。 */
    fun stopListening() {
        server.close()
    }

    /** 待ち受けとすべての接続を閉じる。 */
    override fun close() {
        server.close()
        connections.forEach { it.close() }
    }

    /** 最後の接続。 */
    val last: Connection get() = connections.last()

    /** 1 本の接続。 */
    inner class Connection(private val socket: Socket) {
        private val output: OutputStream = socket.getOutputStream()

        /** 持ち主のエージェント。 */
        val agent: FakeAgent get() = this@FakeAgent

        /** この接続で購読された番号。 */
        val subscriptions: MutableList<Int> = CopyOnWriteArrayList()

        /** 1 行を送る。 */
        @Synchronized
        fun sendLine(line: String) {
            output.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        /** 成功の応答。 */
        fun ok(id: Long, result: JsonElement) {
            sendLine(buildJsonObject { put("id", id); put("ok", true); put("result", result) }.toString())
        }

        /** 失敗の応答。 */
        fun error(id: Long, type: String, message: String, exception: String? = null, stackTrace: String? = null) {
            val error = buildJsonObject {
                put("type", type)
                put("message", message)
                put("exception", exception?.let(::JsonPrimitive) ?: JsonNull)
                put("stackTrace", stackTrace?.let(::JsonPrimitive) ?: JsonNull)
            }
            sendLine(buildJsonObject { put("id", id); put("ok", false); put("error", error) }.toString())
        }

        /** イベントを送る。 */
        fun event(subscription: Int, type: String, tick: Long = 100, fields: JsonObject = JsonObject(emptyMap()), cancelled: Boolean? = null) {
            val event = buildJsonObject {
                put("subscription", subscription)
                put("type", type)
                put("tick", tick)
                put("cancelled", cancelled?.let(::JsonPrimitive) ?: JsonNull)
                put("fields", fields)
            }
            sendLine(buildJsonObject { put("event", event) }.toString())
        }

        /** 既定の応答（subscribe は types をそのまま返し、ping は tick 100、他は {}）。 */
        fun reply(request: JsonObject) {
            val id = request["id"]!!.jsonPrimitive.long
            when (request["op"]!!.jsonPrimitive.content) {
                "subscribe" -> {
                    subscriptions += request["subscription"]!!.jsonPrimitive.int
                    ok(id, buildJsonObject { put("types", request["types"]!!) })
                }
                "unsubscribe" -> {
                    subscriptions.remove(request["subscription"]!!.jsonPrimitive.int)
                    ok(id, JsonObject(emptyMap()))
                }
                "ping" -> ok(id, buildJsonObject { put("tick", 100) })
                else -> ok(id, JsonObject(emptyMap()))
            }
        }

        /** 接続を閉じる。 */
        fun close() {
            runCatching { socket.close() }
        }

        /** hello を確かめ、以後のリクエストを handler に渡す。 */
        fun serve() {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            try {
                val hello = Json.parseToJsonElement(reader.readLine() ?: return).jsonObject
                hellos++
                if (hello["token"]?.jsonPrimitive?.content != token) {
                    error(0, "auth", "invalid token")
                    close()
                    return
                }
                ok(
                    0,
                    buildJsonObject {
                        put("protocol", 1)
                        put("agentVersion", "3.0.0")
                        put("serverName", "Paper")
                        put("serverVersion", "fake")
                        put("minecraftVersion", "1.21.4")
                    },
                )
                while (true) {
                    val line = reader.readLine() ?: break
                    val request = Json.parseToJsonElement(line).jsonObject
                    requests += request
                    handler(request)
                }
            } catch (_: Exception) {
                // テストが接続を閉じた
            } finally {
                close()
            }
        }
    }

    companion object {
        /** 既定のトークン。 */
        const val TOKEN: String = "0123456789abcdef0123456789abcdef"
    }
}

/** リクエストの id。 */
val JsonObject.id: Long get() = this["id"]!!.jsonPrimitive.long

/** リクエストの op。 */
val JsonObject.op: String get() = this["op"]!!.jsonPrimitive.content
