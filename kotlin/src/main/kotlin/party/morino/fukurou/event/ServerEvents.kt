package party.morino.fukurou.event

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * サーバーのイベントの待ち受け（エージェント経由、v3 設計 §2.1）。
 *
 * イベントの種類はクラスの完全修飾名か、Bukkit / Paper の標準のパッケージにあるクラスの単純名（"BlockBreakEvent"）。
 * 種類が見つからなければ [party.morino.fukurou.error.AgentRequestException]。
 */
public interface ServerEvents {
    /** 購読を始め、以後に起きたイベントを溜める記録器を返す。テストの終わりに自動で閉じる。 */
    public suspend fun record(vararg types: String): EventRecorder

    /** 呼んだ後に起きた type のイベントで predicate を満たすものを待つ。待ちが切れたら [EventAssertionError]。 */
    public suspend fun await(
        type: String,
        timeout: Duration = 60.seconds,
        predicate: (ServerEvent) -> Boolean = { true },
    ): ServerEvent

    /** 購読してから action を実行し、type のイベントで predicate を満たすものを待つ（取りこぼさない）。 */
    public suspend fun expect(
        type: String,
        timeout: Duration = 60.seconds,
        predicate: (ServerEvent) -> Boolean = { true },
        action: suspend () -> Unit,
    ): ServerEvent
}

/** 購読中のイベントの記録器。 */
public interface EventRecorder : AutoCloseable {
    /** 解決したイベントのクラスの完全修飾名。 */
    public val types: List<String>

    /** これまでに届いたイベント（届いた順の写し）。 */
    public fun events(): List<ServerEvent>

    /** 既に届いたものを含めて、predicate を満たすイベントを待つ。 */
    public suspend fun await(timeout: Duration = 60.seconds, predicate: (ServerEvent) -> Boolean = { true }): ServerEvent

    /** 既に届いたものを含めて、predicate を満たすイベントが count 件になるまで待つ。 */
    public suspend fun awaitCount(
        count: Int,
        timeout: Duration = 60.seconds,
        predicate: (ServerEvent) -> Boolean = { true },
    ): List<ServerEvent>

    /** これまでに predicate を満たすイベントがあれば [EventAssertionError]。 */
    public fun assertNone(predicate: (ServerEvent) -> Boolean = { true })

    /** duration の間待ち、その間（とそれまで）に predicate を満たすイベントがあれば [EventAssertionError]。 */
    public suspend fun assertNoneWithin(duration: Duration, predicate: (ServerEvent) -> Boolean = { true })

    /** 購読をやめる（冪等）。 */
    override fun close()
}

/** イベントの待ちや否定の検査の失敗。 */
public class EventAssertionError(message: String) : AssertionError(message)
