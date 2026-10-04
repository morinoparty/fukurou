package party.morino.fukurou.engine.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.kyori.adventure.key.Key
import party.morino.fukurou.engine.session.ServerInstance
import party.morino.fukurou.engine.step.StepRunner
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.error.AgentRequestException
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerTaskException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.event.ServerEvents
import party.morino.fukurou.spi.model.AgentEndpoint
import party.morino.fukurou.state.BlockSnapshot
import party.morino.fukurou.state.EntityQuery
import party.morino.fukurou.state.EntitySnapshot
import party.morino.fukurou.state.PlayerSnapshot
import party.morino.fukurou.state.WorldSnapshot
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.Worlds
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * サーバー 1 台ぶんのエージェントへの接続（v3 設計 §1・§2.1）。セッションごとに connect / disconnect する。
 *
 * 状態の取得・tick の待ち・execute・イベントの購読はすべて 1 本の接続（AgentClient）を共有し、レーンから同時に呼んでよい。
 * どの呼び出しもステップ（on "server"）として記録し、待ちはテストの期限とサーバーの生存を確かめる（LogWaiter と同じ）。
 *
 * @property host ステップの記録先を探すサーバー
 * @property typeId サーバーの種類の id（UnsupportedCapabilityException に載せる）
 * @property liveness サーバーと参加中のクライアントの生存確認（死んでいれば投げる）
 * @property checkServer サーバーのプロセスの生存確認（死んでいれば ServerUnavailableException）
 * @property pollInterval 待ちの間に生存を確かめる間隔
 * @property retryInterval 起動時の接続の再試行の間隔
 * @property reconnectTimeout 切れた接続を張り直すときの待ち時間
 * @property grace エージェントの timeoutMs より長く待つ余裕（エージェントの timeout の error を先に受け取る）
 */
internal class AgentAccess(
    private val host: StepHost,
    private val typeId: String,
    private val liveness: () -> Unit,
    private val checkServer: () -> Unit,
    private val pollInterval: Duration = 500.milliseconds,
    private val retryInterval: Duration = 1.seconds,
    private val reconnectTimeout: Duration = 10.seconds,
    private val grace: Duration = 10.seconds,
) {
    /** ServerInstance の持つ接続。 */
    constructor(server: ServerInstance) : this(server, server.type.id, server::checkLiveness, server::checkServerAlive)

    /** 今のセッションのエージェントの接続先。null ならエージェントの無いサーバー（または停止済み）。 */
    @Volatile
    private var endpoint: AgentEndpoint? = null

    /** 今の接続。 */
    @Volatile
    private var client: AgentClient? = null

    /** 張り直しの排他。 */
    private val reconnecting = Mutex()

    /** 開いている記録器（張り直したら購読し直す）。 */
    private val recorders = CopyOnWriteArrayList<AgentEventRecorder>()

    /** 次の購読の番号。 */
    private val nextSubscription = AtomicInteger(1)

    /** イベントの待ち受け。 */
    val events: ServerEvents = AgentServerEvents(this)

    // --- 接続 --------------------------------------------------------------------

    /**
     * エージェントに接続し hello が通るまで 1 秒ごとに再試行する。endpoint が null なら何もしない（エージェントの無い種類）。
     *
     * @throws SetupException timeout までに hello が通らない、またはトークンが拒まれた
     * @throws ServerUnavailableException 待っている間にサーバーが終わった
     */
    suspend fun connect(endpoint: AgentEndpoint?, timeout: Duration) {
        if (endpoint == null) return
        val opened = openWithRetry(endpoint, timeout)
        this.endpoint = endpoint
        client = opened
        val hello = opened.hello
        fun field(key: String): String = (hello[key] as? JsonPrimitive)?.content ?: "?"
        host.log(
            "connected to the fukurou agent ${field("agentVersion")} on ${AgentProtocol.HOST}:${endpoint.port} " +
                "(${field("serverName")} ${field("serverVersion")}, Minecraft ${field("minecraftVersion")})",
        )
    }

    /** 接続を閉じる（冪等）。記録器もすべて閉じる。 */
    fun disconnect() {
        closeRecorders()
        endpoint = null
        client?.close()
        client = null
    }

    /** テストの終わりに、開いている記録器を閉じる。 */
    fun closeRecorders() {
        recorders.toList().forEach { it.close() }
    }

    /** hello が通るまで再試行する。 */
    private suspend fun openWithRetry(endpoint: AgentEndpoint, timeout: Duration): AgentClient {
        val started = TimeSource.Monotonic.markNow()
        var last: Throwable? = null
        while (true) {
            val remaining = timeout - started.elapsedNow()
            if (!remaining.isPositive()) {
                throw SetupException(
                    "the fukurou agent did not answer on ${AgentProtocol.HOST}:${endpoint.port} within ${timeout.inWholeSeconds}s " +
                        "(${last?.message ?: "no attempt"}); check the server log for FukurouAgent errors",
                    last,
                )
            }
            try {
                return AgentClient.open(endpoint.port, endpoint.token, minOf(ATTEMPT_TIMEOUT, remaining))
            } catch (error: AgentErrorException) {
                throw SetupException("the fukurou agent refused the connection: ${error.type}: ${error.remoteMessage}", error)
            } catch (error: AgentProtocolException) {
                throw SetupException("the fukurou agent on ${AgentProtocol.HOST}:${endpoint.port} is not compatible: ${error.message}", error)
            } catch (error: IOException) {
                // まだ待ち受けていない（プラグインの有効化の前）。サーバーが終わっていればすぐ止める
                last = error
            }
            checkServer()
            delay(minOf(retryInterval, (timeout - started.elapsedNow()).coerceAtLeast(Duration.ZERO)))
        }
    }

    /** 接続先。無ければエージェントを使えないサーバー。 */
    private fun requireAgent(): AgentEndpoint = endpoint ?: throw UnsupportedCapabilityException(typeId, "agent")

    /**
     * 切れた接続を 1 回だけ張り直し、開いている記録器を購読し直す。
     *
     * @param broken 切れているのを見た接続（既に別のレーンが張り直していればそれを使う）
     * @throws ServerUnavailableException サーバーが終わっている、または張り直せない
     */
    private suspend fun reconnect(broken: AgentClient?, cause: Throwable?): AgentClient = reconnecting.withLock {
        val current = client
        if (current != null && current !== broken && current.isOpen) return@withLock current
        // 呼び出しの途中で disconnect された（セッションの停止）。エージェントの無いサーバーではなく、止まったサーバーとして扱う
        val target = endpoint ?: throw ServerUnavailableException(
            "the connection to the fukurou agent was closed because the session was stopped (${cause?.message ?: "closed"})",
        )
        // プロセスが終わっていれば張り直さない
        checkServer()
        val fresh = try {
            AgentClient.open(target.port, target.token, reconnectTimeout)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw ServerUnavailableException(
                "lost the connection to the fukurou agent (${cause?.message ?: "closed"}) and could not reconnect: ${error.message}",
            )
        }
        broken?.close()
        client = fresh
        host.warn("reconnected to the fukurou agent after: ${cause?.message ?: "the connection was closed"}; events in between may be missing")
        // 購読は接続ごとなので、開いている記録器を新しい接続で購読し直す
        for (recorder in recorders.toList()) {
            fresh.addHandler(recorder.subscription, recorder::deliver)
            fresh.sendQuietly("subscribe", subscribeArgs(recorder.subscription, recorder.requested))
        }
        fresh
    }

    // --- リクエスト ------------------------------------------------------------------

    /**
     * op を送って result を返す。接続が切れていれば 1 回だけ張り直して送り直す（idempotent でなければ送り直さない）。
     *
     * @param wait エージェントが答えるまで待つ時間（エージェントの timeoutMs より少し長く待つ）
     * @param task execute のタスク名（task_failed の読み替えに使う）
     * @param prepare 送る直前に、使う接続に対して行うこと（イベントの受け手の登録）
     */
    private suspend fun call(
        op: String,
        args: Map<String, JsonElement>,
        wait: Duration = DEFAULT_TIMEOUT + grace,
        idempotent: Boolean = true,
        task: String? = null,
        prepare: (AgentClient) -> Unit = {},
    ): JsonElement {
        requireAgent()
        var reconnected = false
        var current = client?.takeIf { it.isOpen } ?: reconnect(client, null).also { reconnected = true }
        while (true) {
            try {
                prepare(current)
                return exchange(current, op, args, wait, task)
            } catch (lost: AgentConnectionLostException) {
                // プロセスが終わっていれば ServerUnavailableException
                checkServer()
                if (reconnected) {
                    throw ServerUnavailableException("the connection to the fukurou agent was lost again during $op: ${lost.message}")
                }
                current = reconnect(current, lost)
                reconnected = true
                if (!idempotent) {
                    throw AgentRequestException(
                        "connection_lost",
                        "the connection to the fukurou agent was lost during $op${task?.let { " '$it'" }.orEmpty()} " +
                            "(${lost.message}); it was reconnected, but the request was not sent again because it may have run",
                    )
                }
            }
        }
    }

    /** 1 回送り、生存を確かめながら応答を待つ。 */
    private suspend fun exchange(client: AgentClient, op: String, args: Map<String, JsonElement>, wait: Duration, task: String?): JsonElement {
        val pending = client.send(op, args)
        try {
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                val response = withTimeoutOrNull(pollInterval) { pending.response.await() }
                if (response != null) {
                    return try {
                        AgentClient.result(response)
                    } catch (error: AgentErrorException) {
                        throw mapError(error, op, task)
                    }
                }
                // 待っている間にサーバーやクライアントが落ちたら、すぐ失敗させる
                liveness()
                if (started.elapsedNow() >= wait) {
                    throw HarnessTimeoutException("the fukurou agent did not answer $op within ${wait.inWholeSeconds}s")
                }
            }
        } finally {
            client.forget(pending.id)
        }
    }

    /** エージェントの error を公開の例外に読み替える。 */
    private fun mapError(error: AgentErrorException, op: String, task: String?): Throwable = when (error.type) {
        "auth", "bad_request", "not_found" -> AgentRequestException(error.type, "$op: ${error.remoteMessage}")
        "task_failed" -> ServerTaskException(task ?: op, error.exception, error.remoteMessage, error.stackTrace)
        "timeout" -> HarnessTimeoutException("the server did not run $op in time: ${error.remoteMessage}")
        else -> FukurouException("the fukurou agent failed on $op: ${error.type}: ${error.remoteMessage}", error)
    }

    // --- 状態 --------------------------------------------------------------------

    /** 1 ブロック（query / "block x y z"）。 */
    suspend fun block(at: BlockPos, world: Key): BlockSnapshot {
        requireAgent()
        return StepRunner.step(host, StepRunner.ON_SERVER, QUERY, "block ${at.x} ${at.y} ${at.z}${worldSuffix(world)}") { _, _ ->
            val args = mapOf(
                "world" to JsonPrimitive(world.asString()),
                "x" to JsonPrimitive(at.x),
                "y" to JsonPrimitive(at.y),
                "z" to JsonPrimitive(at.z),
            )
            AgentDecoder.block(call("block", args))
        }
    }

    /** エンティティの一覧（query / "entities …"）。 */
    suspend fun entities(query: EntityQuery): List<EntitySnapshot> {
        requireAgent()
        return StepRunner.step(host, StepRunner.ON_SERVER, QUERY, entitiesLabel(query)) { _, _ ->
            AgentDecoder.entities(call("entities", entitiesArgs(query)))
        }
    }

    /** ワールドの状態（query / "world <key>"）。 */
    suspend fun worldState(world: Key): WorldSnapshot {
        requireAgent()
        return StepRunner.step(host, StepRunner.ON_SERVER, QUERY, "world ${world.asString()}") { _, _ ->
            AgentDecoder.world(call("world", mapOf("world" to JsonPrimitive(world.asString()))))
        }
    }

    /** プレイヤーの状態（query / "player <name>"）。 */
    suspend fun player(name: String): PlayerSnapshot {
        requireAgent()
        return StepRunner.step(host, StepRunner.ON_SERVER, QUERY, "player $name") { _, _ ->
            AgentDecoder.player(call("player", mapOf("name" to JsonPrimitive(name))))
        }
    }

    /** 今の tick（query / "tick"）。 */
    suspend fun currentTick(): Long {
        requireAgent()
        return StepRunner.step(host, StepRunner.ON_SERVER, QUERY, "tick") { _, _ -> tickOf(call("ping", emptyMap())) }
    }

    /** ticks だけ tick が進むのを待つ（wait_ticks / "20"）。 */
    suspend fun awaitTicks(ticks: Int) {
        require(ticks in 1..MAX_TICKS) { "ticks must be in 1..$MAX_TICKS, got $ticks" }
        requireAgent()
        StepRunner.step(host, StepRunner.ON_SERVER, "wait_ticks", ticks.toString()) { _, _ ->
            // timeoutMs は遅れの余裕だけ（エージェントが ticks × 50 ms を足す）。こちらはさらに grace だけ長く待つ
            call(
                "awaitTicks",
                mapOf("ticks" to JsonPrimitive(ticks), "timeoutMs" to JsonPrimitive(AWAIT_TICKS_SLACK.inWholeMilliseconds)),
                wait = awaitTicksWait(ticks, grace),
            )
        }
    }

    /** コンパニオンプラグインのタスクを実行する（execute / task）。戻り値が null なら JsonNull。 */
    suspend fun execute(task: String, args: JsonElement?, timeout: Duration): JsonElement {
        // エージェントは timeoutMs を 1 ミリ秒〜1 日に限る（範囲外は bad_request）ので、送る前に同じ範囲で確かめる
        require(timeout >= 1.milliseconds && timeout <= 1.days) { "timeout must be between 1ms and 1d, got $timeout" }
        requireAgent()
        return StepRunner.step(host, StepRunner.ON_SERVER, "execute", task) { _, _ ->
            val request = mapOf(
                "task" to JsonPrimitive(task),
                "args" to (args ?: JsonNull),
                "timeoutMs" to JsonPrimitive(timeout.inWholeMilliseconds),
            )
            val result = call("execute", request, wait = timeout + grace, idempotent = false, task = task)
            (result as? JsonObject)?.get("value") ?: JsonNull
        }
    }

    // --- イベント ------------------------------------------------------------------

    /**
     * 購読を始めて記録器を返す（ステップは記録しない。呼び出し側が subscribe / await_event として記録する）。
     *
     * @throws AgentRequestException イベントの種類が見つからない（not_found）など
     */
    suspend fun openRecorder(types: List<String>): AgentEventRecorder {
        require(types.isNotEmpty()) { "give at least one event type" }
        requireAgent()
        val recorder = AgentEventRecorder(nextSubscription.getAndIncrement(), types, this)
        val result = try {
            // 応答より先にイベントが届くことがあるので、受け手を先に登録する
            call("subscribe", subscribeArgs(recorder.subscription, types), prepare = { it.addHandler(recorder.subscription, recorder::deliver) })
        } catch (error: Throwable) {
            client?.let { current ->
                current.removeHandler(recorder.subscription)
                // 取り消し・時間切れ・接続の問題では、エージェントが既に購読を登録したかもしれないので外しておく
                // （AgentRequestException ならエージェントは何も登録していない）
                if (error !is AgentRequestException) {
                    current.sendQuietly("unsubscribe", mapOf("subscription" to JsonPrimitive(recorder.subscription)))
                }
            }
            throw error
        }
        val resolved = ((result as? JsonObject)?.get("types") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        recorder.resolved(resolved ?: types)
        recorders += recorder
        return recorder
    }

    /** 記録器から: 購読をやめる。応答は待たず、失敗しても投げない。 */
    fun closeRecorder(recorder: AgentEventRecorder) {
        recorders.remove(recorder)
        val current = client ?: return
        current.removeHandler(recorder.subscription)
        current.sendQuietly("unsubscribe", mapOf("subscription" to JsonPrimitive(recorder.subscription)))
    }

    /** 記録器の step（subscribe / await_event / assert_no_event）の記録先。 */
    internal val stepHost: StepHost get() = host

    /**
     * signal が変わるたびに check を呼び、null でない値が出るまで待つ（LogWaiter と同じ規則）。
     *
     * 上限は min(timeout, テストの残り時間)。テストの期限が先に尽きたら HarnessTimeoutException、
     * そうでなければ null を返す（呼び出し側が EventAssertionError にするか、成功とする）。
     *
     * @param what HarnessTimeoutException のメッセージに出す待ちの説明
     */
    suspend fun <T : Any> waitFor(what: String, timeout: Duration, signal: StateFlow<Long>, check: () -> T?): T? {
        val remaining = StepRunner.deadlineOf(host)?.remaining()
        val deadlineWins = remaining != null && remaining < timeout
        val limit = if (remaining != null && deadlineWins) remaining else timeout
        val found = withTimeoutOrNull(limit) {
            var result: T? = null
            while (result == null) {
                val seen = signal.value
                result = check()
                if (result == null) {
                    // 待っている間にサーバーやクライアントが落ちたら、時間切れを待たずにすぐ失敗させる
                    liveness()
                    ensureConnected()
                    withTimeoutOrNull(pollInterval) { signal.first { it != seen } }
                }
            }
            result
        }
        if (found != null) return found
        // 最後の待ちの間に届いたものを取りこぼさない
        check()?.let { return it }
        if (deadlineWins) {
            val deadline = StepRunner.deadlineOf(host)
            throw HarnessTimeoutException(
                "the test exceeded its timeout of ${deadline?.timeout?.inWholeSeconds ?: limit.inWholeSeconds}s while waiting for $what",
            )
        }
        return null
    }

    /** イベントを待つ間に接続が切れていたら張り直す（購読もし直す）。 */
    private suspend fun ensureConnected() {
        val current = client
        if (endpoint == null || current == null || current.isOpen) return
        checkServer()
        reconnect(current, AgentConnectionLostException("the connection to the fukurou agent was lost while waiting for events"))
    }

    internal companion object {
        /** 状態の取得のアクション名。 */
        private const val QUERY = "query"

        /** awaitTicks の上限（1 時間）。 */
        private const val MAX_TICKS = 72_000

        /** 1 tick のミリ秒。 */
        private const val MILLIS_PER_TICK = 50L

        /** エージェントの既定の timeoutMs（30 秒）。 */
        private val DEFAULT_TIMEOUT: Duration = 30.seconds

        /**
         * awaitTicks の timeoutMs（サーバーの遅れの余裕）。エージェントは ticks × 50 ms + timeoutMs まで待つ。
         */
        val AWAIT_TICKS_SLACK: Duration = DEFAULT_TIMEOUT

        /**
         * awaitTicks の応答を待つ時間。エージェントの待ち（ticks × 50 ms + AWAIT_TICKS_SLACK）より grace だけ長くし、
         * エージェントの timeout の error を先に受け取る。
         */
        fun awaitTicksWait(ticks: Int, grace: Duration): Duration = (ticks * MILLIS_PER_TICK).milliseconds + AWAIT_TICKS_SLACK + grace

        /** 起動時の接続 1 回の待ち。 */
        private val ATTEMPT_TIMEOUT: Duration = 5.seconds

        /** ping / awaitTicks の result の tick。 */
        private fun tickOf(result: JsonElement): Long =
            ((result as? JsonObject)?.get("tick") as? JsonPrimitive)?.longOrNull
                ?: throw FukurouException("the fukurou agent answered without a tick: $result")

        /** 既定（overworld）でないワールドだけラベルに付ける。 */
        private fun worldSuffix(world: Key): String = if (world == Worlds.OVERWORLD) "" else " in ${world.asString()}"

        /** subscribe の引数。 */
        private fun subscribeArgs(subscription: Int, types: List<String>): Map<String, JsonElement> =
            mapOf("subscription" to JsonPrimitive(subscription), "types" to JsonArray(types.map(::JsonPrimitive)))

        /** entities の引数（world が無ければ near の world を使う）。 */
        private fun entitiesArgs(query: EntityQuery): Map<String, JsonElement> = buildMap {
            (query.world ?: query.near?.world)?.let { put("world", JsonPrimitive(it.asString())) }
            query.type?.let { put("type", JsonPrimitive(it.asString())) }
            query.near?.let { near ->
                put(
                    "near",
                    JsonObject(
                        mapOf(
                            "x" to JsonPrimitive(near.x),
                            "y" to JsonPrimitive(near.y),
                            "z" to JsonPrimitive(near.z),
                            "radius" to JsonPrimitive(query.radius),
                        ),
                    ),
                )
            }
            query.tag?.let { put("tag", JsonPrimitive(it)) }
            put("limit", JsonPrimitive(query.limit))
        }

        /** entities のラベル（指定した条件だけ）。 */
        private fun entitiesLabel(query: EntityQuery): String = buildList {
            add("entities")
            query.type?.let { add("type=${it.asString()}") }
            (query.world ?: query.near?.world)?.let { add("world=${it.asString()}") }
            query.near?.let { add("near=${it.x},${it.y},${it.z} radius=${query.radius}") }
            query.tag?.let { add("tag=$it") }
            if (query.limit != EntityQuery().limit) add("limit=${query.limit}")
        }.joinToString(" ")
    }
}
