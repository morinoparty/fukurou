package party.morino.fukurou.engine.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import party.morino.fukurou.event.ServerEvent
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * エージェントへの 1 本の TCP 接続（v3 設計 §1.2）。1 行 1 JSON で、リクエストと応答は id で対応づける。
 *
 * 応答とイベントは専用の読み取りスレッドが受け取り、応答は待っている呼び出しへ、イベントは購読ごとの受け手へ渡す。
 * 送信は排他するので、複数のレーンから同時に呼んでよい。1 つのリクエストの取り消しでは接続を閉じない
 * （他のレーンも同じ接続を使っている）。応答の届かなかったリクエストは捨てる。
 *
 * @property socket 接続済みのソケット
 * @property hello hello の result（agentVersion など）
 * @param input ソケットの入力（hello の読み残しを含む）
 * @param output ソケットの出力
 */
internal class AgentClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val hello: JsonObject,
) : AutoCloseable {
    /** 応答待ちのリクエスト。 */
    class Pending(val id: Long, val response: CompletableDeferred<JsonObject>)

    /** 次のリクエストの id（hello が 0）。 */
    private val nextId = AtomicLong(1)

    /** id → 応答待ち。 */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    /** 購読の番号 → イベントの受け手。 */
    private val handlers = ConcurrentHashMap<Int, (ServerEvent) -> Unit>()

    /** 送信の排他。 */
    private val writeLock = Any()

    /** 閉じた理由。null なら開いている。 */
    @Volatile
    private var closedBy: AgentConnectionLostException? = null

    /** 接続が開いているか。 */
    val isOpen: Boolean get() = closedBy == null

    /** 応答とイベントを読むスレッド。 */
    private val reader: Thread = Thread.ofPlatform().daemon().name("fukurou-agent-${socket.port}").start(::readLoop)

    /**
     * op を送り、応答待ちを返す。応答は [Pending.response] に入る（error でもそのまま。読み替えは [result]）。
     * 待つのをやめたら [forget] する。
     *
     * @throws AgentConnectionLostException 接続が切れている、または送れなかった
     */
    fun send(op: String, args: Map<String, JsonElement> = emptyMap()): Pending {
        val id = nextId.getAndIncrement()
        // 大きすぎるリクエスト（IllegalArgumentException）は応答待ちを登録する前に断る
        val bytes = message(id, op, args)
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            write(bytes)
        } catch (error: AgentConnectionLostException) {
            pending.remove(id)
            throw error
        }
        // 送る前後で閉じられたなら、読み取りスレッドが既に応答待ちを片付けているかもしれない
        closedBy?.let { deferred.completeExceptionally(it) }
        return Pending(id, deferred)
    }

    /** 応答を待たない送信（unsubscribe）。失敗しても投げない。 */
    fun sendQuietly(op: String, args: Map<String, JsonElement> = emptyMap()) {
        if (!isOpen) return
        try {
            write(message(nextId.getAndIncrement(), op, args))
        } catch (_: AgentConnectionLostException) {
            // 切れた接続の購読はエージェントが捨てる
        }
    }

    /** 応答を待つのをやめる（取り消し・期限切れ）。後から届いた応答は捨てる。 */
    fun forget(id: Long) {
        pending.remove(id)
    }

    /** 購読 subscription のイベントの受け手を登録する。subscribe を送るより前に登録する（応答より先にイベントが届くことがある）。 */
    fun addHandler(subscription: Int, handler: (ServerEvent) -> Unit) {
        handlers[subscription] = handler
    }

    /** 購読 subscription のイベントの受け手を外す。 */
    fun removeHandler(subscription: Int) {
        handlers.remove(subscription)
    }

    /** 接続を閉じる（冪等）。応答待ちはすべて AgentConnectionLostException で終わる。 */
    override fun close() {
        shutdown(AgentConnectionLostException("the connection to the fukurou agent was closed"))
    }

    override fun toString(): String = "AgentClient(${AgentProtocol.HOST}:${socket.port})"

    /** 1 行を書く。 */
    private fun write(line: ByteArray) {
        closedBy?.let { throw AgentConnectionLostException(it.message.orEmpty(), it) }
        try {
            synchronized(writeLock) {
                output.write(line)
                output.flush()
            }
        } catch (error: IOException) {
            val lost = AgentConnectionLostException("could not write to the fukurou agent (${error.javaClass.simpleName}: ${error.message})", error)
            shutdown(lost)
            throw lost
        }
    }

    /** 読み取りスレッドの本体。切れるまで 1 行ずつ読んで振り分ける。 */
    private fun readLoop() {
        val reason = try {
            while (true) {
                val line = readLine(input, AgentProtocol.MAX_LINE_BYTES)
                    ?: break
                if (line.isBlank()) continue
                dispatch(line)
            }
            AgentConnectionLostException("the fukurou agent closed the connection")
        } catch (error: IOException) {
            AgentConnectionLostException("the connection to the fukurou agent failed (${error.javaClass.simpleName}: ${error.message})", error)
        } catch (error: RuntimeException) {
            AgentConnectionLostException("the fukurou agent sent an unreadable message (${error.javaClass.simpleName}: ${error.message})", error)
        }
        shutdown(reason)
    }

    /** 1 行の JSON を応答かイベントとして振り分ける。 */
    private fun dispatch(line: String) {
        val message = try {
            AgentProtocol.JSON.parseToJsonElement(line) as? JsonObject
        } catch (error: SerializationException) {
            throw AgentProtocolException("the fukurou agent sent invalid JSON: ${error.message}", error)
        } ?: throw AgentProtocolException("the fukurou agent sent a message that is not an object")
        val event = message["event"]
        if (event is JsonObject) {
            val subscription = (event["subscription"] as? JsonPrimitive)?.intOrNull ?: return
            val handler = handlers[subscription] ?: return
            // 受け手の失敗で接続を止めない（受け手はバッファに積むだけ）
            runCatching { handler(AgentDecoder.event(event)) }
            return
        }
        val id = (message["id"] as? JsonPrimitive)?.longOrNull ?: return
        // 待つのをやめた（forget した）リクエストの応答は捨てる
        pending.remove(id)?.complete(message)
    }

    /** 閉じる。最初の理由を残し、応答待ちを終わらせる。 */
    private fun shutdown(reason: AgentConnectionLostException) {
        synchronized(this) {
            if (closedBy == null) closedBy = reason
        }
        runCatching { socket.close() }
        val cause = closedBy ?: reason
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(cause) }
    }

    companion object {
        /**
         * 接続して hello を送り、通れば開いた接続を返す。取り消されたらソケットを閉じて抜ける（RconChannel と同じ）。
         *
         * @param port エージェントのポート
         * @param token hello のトークン
         * @param timeout 接続と hello の応答のそれぞれの待ち時間
         * @throws AgentErrorException hello が拒まれた（type が auth ならトークンの誤り）
         * @throws AgentProtocolException hello の応答の形や版が違う
         * @throws IOException 接続できない・応答が無い
         */
        suspend fun open(port: Int, token: String, timeout: Duration): AgentClient {
            val socket = Socket()
            try {
                return coroutineScope {
                    // ブロックしている connect / read は割り込みでは止まらないので、取り消されたらソケットを閉じる。
                    // hello が通った後は接続を使い続けるので閉じない
                    val opened = AtomicBoolean(false)
                    // UNDISPATCHED: 走り出す前に取り消されても finally でソケットを閉じる
                    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { if (!opened.get()) socket.close() }
                    }
                    try {
                        runInterruptible(Dispatchers.IO) { handshake(socket, port, token, timeout) }.also { opened.set(true) }
                    } catch (error: IOException) {
                        // 取り消しで閉じたための失敗は、接続の失敗ではなく取り消しとして伝える
                        currentCoroutineContext().ensureActive()
                        throw error
                    } finally {
                        closer.cancel()
                    }
                }
            } catch (error: Throwable) {
                socket.close()
                throw error
            }
        }

        /** 接続・hello の送信・応答の確認。 */
        private fun handshake(socket: Socket, port: Int, token: String, timeout: Duration): AgentClient {
            val millis = timeout.inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            socket.connect(InetSocketAddress(AgentProtocol.HOST, port), millis)
            socket.tcpNoDelay = true
            socket.soTimeout = millis
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val hello = buildJsonObject {
                put("id", JsonPrimitive(0))
                put("op", JsonPrimitive("hello"))
                put("token", JsonPrimitive(token))
                put("protocol", JsonPrimitive(AgentProtocol.VERSION))
            }
            output.write(line(hello))
            output.flush()
            val text = readLine(input, AgentProtocol.MAX_LINE_BYTES)
                ?: throw AgentConnectionLostException("the fukurou agent closed the connection before answering hello")
            val response = try {
                AgentProtocol.JSON.parseToJsonElement(text) as? JsonObject
            } catch (error: SerializationException) {
                throw AgentProtocolException("the fukurou agent answered hello with invalid JSON: ${error.message}", error)
            } ?: throw AgentProtocolException("the fukurou agent answered hello with a non-object")
            val result = result(response) as? JsonObject
                ?: throw AgentProtocolException("the fukurou agent answered hello without a result object")
            val protocol = (result["protocol"] as? JsonPrimitive)?.intOrNull
            if (protocol != AgentProtocol.VERSION) {
                throw AgentProtocolException("the fukurou agent speaks protocol $protocol, but this fukurou speaks ${AgentProtocol.VERSION}")
            }
            // 以後は読み取りスレッドが切れるまで待つ
            socket.soTimeout = 0
            return AgentClient(socket, input, output, result)
        }

        /**
         * 応答の result を取り出す。ok でなければ AgentErrorException。
         *
         * @return result（無ければ JsonNull）
         */
        fun result(response: JsonObject): JsonElement {
            val ok = (response["ok"] as? JsonPrimitive)?.booleanOrNull
            if (ok != true) throw AgentErrorException.of(response["error"])
            return response["result"] ?: JsonNull
        }

        /** リクエストの 1 行。 */
        private fun message(id: Long, op: String, args: Map<String, JsonElement>): ByteArray =
            line(JsonObject(linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "op" to JsonPrimitive(op)) + args))

        /** JSON を改行付きの UTF-8 にする（kotlinx の出力には生の改行が出ない）。 */
        private fun line(message: JsonElement): ByteArray {
            val bytes = (AgentProtocol.JSON.encodeToString(JsonElement.serializer(), message) + "\n").toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= AgentProtocol.MAX_LINE_BYTES) { "the request to the fukurou agent exceeds 16 MiB (${bytes.size} bytes)" }
            return bytes
        }

        /**
         * \n までの 1 行を読む（末尾の \r は落とす）。行が maxBytes を超えたら AgentProtocolException。
         *
         * @return 行。何も読まずに EOF なら null
         */
        fun readLine(input: InputStream, maxBytes: Int): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte == -1) {
                    if (buffer.size() == 0) return null
                    throw AgentConnectionLostException("the fukurou agent closed the connection in the middle of a message")
                }
                if (byte == '\n'.code) break
                if (buffer.size() >= maxBytes) throw AgentProtocolException("a message from the fukurou agent exceeds $maxBytes bytes")
                buffer.write(byte)
            }
            return buffer.toString(StandardCharsets.UTF_8).removeSuffix("\r")
        }
    }
}
