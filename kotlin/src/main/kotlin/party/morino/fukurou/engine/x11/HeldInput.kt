package party.morino.fukurou.engine.x11

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.fukurou.player.KeySym
import party.morino.fukurou.player.MouseButton
import kotlin.time.Duration

/** 押したままの入力を離す相手（MinecraftWindow。テストでは偽物を渡す）。 */
internal interface HeldInputDevice {
    /** 押したままのキーを離す。 */
    suspend fun keyUp(key: KeySym)

    /** 押したままのボタンを離す。 */
    suspend fun mouseUp(button: MouseButton)
}

/**
 * プレイヤー 1 人の押したままのキーとボタンの記録（v3 設計 §2.2）。
 *
 * 押す前に記録し、離せたときだけ消す。送信に失敗したキーも押されている可能性があるので残し、テストの終わりの
 * releaseAll でもう一度離す。レーンからも同時に触れるので同期する。
 */
internal class HeldInput {
    /** 押したままのキー（押した順）。 */
    private val keys = LinkedHashSet<KeySym>()

    /** 押したままのボタン（押した順）。 */
    private val buttons = LinkedHashSet<MouseButton>()

    /** 何も押していないか。 */
    val isEmpty: Boolean get() = synchronized(this) { keys.isEmpty() && buttons.isEmpty() }

    /** 押したままのキー（押した順の写し）。 */
    fun keys(): List<KeySym> = synchronized(this) { keys.toList() }

    /** 押したままのボタン（押した順の写し）。 */
    fun buttons(): List<MouseButton> = synchronized(this) { buttons.toList() }

    /** key を押す前に記録する。 */
    fun keyPressed(key: KeySym) {
        synchronized(this) { keys.add(key) }
    }

    /** key を離せた。 */
    fun keyReleased(key: KeySym) {
        synchronized(this) { keys.remove(key) }
    }

    /** button を押す前に記録する。 */
    fun buttonPressed(button: MouseButton) {
        synchronized(this) { buttons.add(button) }
    }

    /** button を離せた。 */
    fun buttonReleased(button: MouseButton) {
        synchronized(this) { buttons.remove(button) }
    }

    /** 記録を捨てる（クライアントを起動し直した。新しいクライアントは何も押していない）。 */
    fun clear() {
        synchronized(this) {
            keys.clear()
            buttons.clear()
        }
    }

    /**
     * 押したままのものを、ボタン → キーの順に、それぞれ押したのと逆の順で device で離す。
     * 1 つ失敗しても残りは離し、記録は空にする。投げない（取り消しも含めて失敗として返す）。
     *
     * @return 離せなかったもの（"w: <理由>" の形）。すべて離せたら空
     */
    suspend fun releaseAll(device: HeldInputDevice): List<String> {
        val (heldKeys, heldButtons) = synchronized(this) {
            (keys.toList() to buttons.toList()).also { clear() }
        }
        val failures = mutableListOf<String>()
        for (button in heldButtons.asReversed()) {
            runCatching { device.mouseUp(button) }.onFailure { failures += "${button.label}: ${it.message}" }
        }
        for (key in heldKeys.asReversed()) {
            runCatching { device.keyUp(key) }.onFailure { failures += "${key.keysym}: ${it.message}" }
        }
        return failures
    }

    /**
     * テストの終わりに押したままのものを離す（PlayerSession.releaseHeldInput の本体）。投げない。
     * 何も押していなければ何もしない。離せたかどうかにかかわらず記録は空にする。
     *
     * @param device 今のウィンドウ（まだ見つかっていなければ null）
     * @param lock プレイヤーの入力のロック。置き去りのレーンが持ち続けても止まらないよう timeout まで待つ
     * @return 離せなかった理由（すべて離せた・何も押していなければ null）
     */
    suspend fun releaseAtEnd(device: HeldInputDevice?, lock: Mutex, timeout: Duration): String? {
        if (isEmpty) return null
        val problem = try {
            if (device == null) {
                "the Minecraft window was not found"
            } else {
                val failures = withTimeoutOrNull(timeout) { lock.withLock { releaseAll(device) } }
                when {
                    failures == null -> "timed out after $timeout"
                    failures.isNotEmpty() -> failures.joinToString("; ")
                    else -> null
                }
            }
        } catch (error: Exception) {
            error.message ?: error.javaClass.name
        }
        clear()
        return problem
    }

    /** 押したまま実行する補助。 */
    companion object {
        /**
         * block を実行し、最後に（例外・取り消しでも）NonCancellable で release を実行する。
         * release の失敗は、block が投げていればその suppressed に足し、成功していればそのまま投げる。
         */
        suspend fun <T> releasingAfter(release: suspend () -> Unit, block: suspend () -> T): T {
            val result = try {
                block()
            } catch (error: Throwable) {
                withContext(NonCancellable) { runCatching { release() }.exceptionOrNull()?.let(error::addSuppressed) }
                throw error
            }
            withContext(NonCancellable) { release() }
            return result
        }

        /** items を順に action で離す。1 つ失敗しても残りを離し、最初の失敗（残りは suppressed）を投げる。 */
        suspend fun <E> releaseEach(items: List<E>, action: suspend (E) -> Unit) {
            var first: Throwable? = null
            for (item in items) {
                try {
                    action(item)
                } catch (error: Throwable) {
                    first?.addSuppressed(error) ?: run { first = error }
                }
            }
            first?.let { throw it }
        }
    }
}
