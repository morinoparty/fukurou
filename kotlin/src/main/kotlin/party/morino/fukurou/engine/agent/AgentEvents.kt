package party.morino.fukurou.engine.agent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import party.morino.fukurou.engine.step.StepRunner
import party.morino.fukurou.event.EventAssertionError
import party.morino.fukurou.event.EventRecorder
import party.morino.fukurou.event.ServerEvent
import party.morino.fukurou.event.ServerEvents
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * ServerEvents の実装（v3 設計 §2.1）。
 *
 * record は subscribe のステップ、await は await_event のステップを 1 つ記録する。expect は subscribe → action の中のステップ →
 * await_event の順に記録する。
 *
 * @property access 持ち主の接続
 */
internal class AgentServerEvents(private val access: AgentAccess) : ServerEvents {
    override suspend fun record(vararg types: String): EventRecorder {
        val list = types.toList()
        require(list.isNotEmpty()) { "give at least one event type" }
        return StepRunner.step(access.stepHost, StepRunner.ON_SERVER, "subscribe", list.joinToString(", ")) { _, _ ->
            access.openRecorder(list)
        }
    }

    override suspend fun await(type: String, timeout: Duration, predicate: (ServerEvent) -> Boolean): ServerEvent =
        StepRunner.step(access.stepHost, StepRunner.ON_SERVER, "await_event", type) { _, _ ->
            // 呼んだ後に起きたイベントだけを見る（ここで購読を始める）
            val recorder = access.openRecorder(listOf(type))
            try {
                recorder.awaitMatch(timeout, predicate)
            } finally {
                recorder.close()
            }
        }

    override suspend fun expect(
        type: String,
        timeout: Duration,
        predicate: (ServerEvent) -> Boolean,
        action: suspend () -> Unit,
    ): ServerEvent {
        // 操作より先に購読するので、操作の直後に起きたイベントを取りこぼさない
        val recorder = record(type)
        try {
            action()
            return recorder.await(timeout, predicate)
        } finally {
            withContext(NonCancellable) { recorder.close() }
        }
    }
}

/**
 * 購読中のイベントを届いた順に溜める記録器。イベントは接続の読み取りスレッドから届く。
 *
 * @property subscription 購読の番号（クライアントが決める）
 * @property requested 指定されたイベントの種類（張り直したときに購読し直す）
 * @property access 持ち主の接続
 */
internal class AgentEventRecorder(
    val subscription: Int,
    val requested: List<String>,
    private val access: AgentAccess,
) : EventRecorder {
    /** 解決した種類（subscribe の応答）。 */
    @Volatile
    override var types: List<String> = requested
        private set

    /** 届いたイベント。 */
    private val received = CopyOnWriteArrayList<ServerEvent>()

    /** 届いた件数。待ちを起こすのに使う。 */
    private val signal = MutableStateFlow(0L)

    /** 閉じたか。 */
    private val closed = AtomicBoolean(false)

    /** ステップのラベル。 */
    private val label: String get() = types.joinToString(", ")

    /** subscribe の応答で解決した種類を受け取る。 */
    fun resolved(types: List<String>) {
        this.types = types
    }

    /** 接続から: イベントが届いた。閉じた後のものは捨てる。 */
    fun deliver(event: ServerEvent) {
        if (closed.get()) return
        received += event
        signal.update { it + 1 }
    }

    override fun events(): List<ServerEvent> = received.toList()

    override suspend fun await(timeout: Duration, predicate: (ServerEvent) -> Boolean): ServerEvent =
        StepRunner.step(access.stepHost, StepRunner.ON_SERVER, "await_event", label) { _, _ -> awaitMatch(timeout, predicate) }

    override suspend fun awaitCount(count: Int, timeout: Duration, predicate: (ServerEvent) -> Boolean): List<ServerEvent> {
        require(count >= 1) { "count must be at least 1, got $count" }
        return StepRunner.step(access.stepHost, StepRunner.ON_SERVER, "await_event", "$count x $label") { _, _ ->
            access.waitFor("$count $label events", timeout, signal) {
                received.filter(predicate).takeIf { it.size >= count }?.take(count)
            } ?: throw EventAssertionError(
                "expected $count $label events within ${StepRunner.seconds(timeout)}s, got ${received.count(predicate)} matching ${summary()}",
            )
        }
    }

    override fun assertNone(predicate: (ServerEvent) -> Boolean) {
        StepRunner.instant(access.stepHost, StepRunner.ON_SERVER, "assert_no_event", label) {
            received.firstOrNull(predicate)?.let { throw unexpected(it) }
        }
    }

    override suspend fun assertNoneWithin(duration: Duration, predicate: (ServerEvent) -> Boolean) {
        StepRunner.step(access.stepHost, StepRunner.ON_SERVER, "assert_no_event", "$label ${StepRunner.seconds(duration)}s") { _, _ ->
            // 期間の間に一致するものが届いたら、期間の終わりを待たずに失敗させる
            access.waitFor("no $label events", duration, signal) { received.firstOrNull(predicate) }?.let { throw unexpected(it) }
        }
    }

    /** 購読をやめる（冪等）。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        access.closeRecorder(this)
    }

    /** 既に届いたものを含めて、predicate を満たす最初のイベントを待つ。だめなら EventAssertionError。 */
    suspend fun awaitMatch(timeout: Duration, predicate: (ServerEvent) -> Boolean): ServerEvent =
        access.waitFor("a $label event", timeout, signal) { received.firstOrNull(predicate) }
            ?: throw EventAssertionError("no matching $label event within ${StepRunner.seconds(timeout)}s; ${summary()}")

    /** 来てはいけないイベントが来た。 */
    private fun unexpected(event: ServerEvent): EventAssertionError =
        EventAssertionError("unexpected ${event.simpleName} at tick ${event.tick}: ${event.fields}")

    /** 失敗のメッセージに添える、届いたイベントの要約（最後の数件）。 */
    private fun summary(): String {
        val all = received.toList()
        if (all.isEmpty()) return "received no $label events"
        val shown = all.takeLast(SUMMARY_EVENTS).joinToString("; ") { "${it.simpleName}@${it.tick} ${it.fields}" }
        return "received ${all.size} $label events, last: $shown"
    }

    private companion object {
        /** 要約に載せるイベントの数。 */
        private const val SUMMARY_EVENTS = 3
    }
}
