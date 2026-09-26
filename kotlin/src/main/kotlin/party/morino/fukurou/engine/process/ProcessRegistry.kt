package party.morino.fukurou.engine.process

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * 生きている外部プロセスの一覧と、JVM に 1 つだけのシャットダウンフック（cli.py:305,348）。
 *
 * Gradle のキャンセルや SIGTERM で JVM が終わるとき、クライアント → Xvfb → サーバーの順に 5 秒の猶予で止め、
 * その後に登録された処理（JUnit 側の LeaseRegistry による interrupted の記録など）を呼ぶ。
 */
internal object ProcessRegistry {
    /**
     * プロセスの種類。シャットダウンでは宣言順（クライアント → Xvfb → サーバー）に止める。
     */
    enum class Kind {
        /** Minecraft のクライアント（PortableMC）。 */
        CLIENT,

        /** 仮想ディスプレイ（Xvfb）。 */
        DISPLAY,

        /** ゲームサーバー。 */
        SERVER,
    }

    /** 登録中のプロセスと種類。 */
    private val entries = CopyOnWriteArrayList<Pair<Kind, ManagedProcess>>()

    /** プロセスを止めた後に呼ぶ処理。 */
    private val shutdownListeners = CopyOnWriteArrayList<() -> Unit>()

    /** フックを入れたか。 */
    private val hookInstalled = AtomicBoolean(false)

    /** シャットダウンの処理を始めたか（2 回実行しない）。 */
    private val shuttingDown = AtomicBoolean(false)

    /** process を登録する。最初の登録でシャットダウンフックを入れる。 */
    fun register(kind: Kind, process: ManagedProcess) {
        installHook()
        // 終わったプロセスはついでに外す
        entries.removeIf { !it.second.isAlive }
        entries.add(kind to process)
    }

    /** process の登録を外す（止め終わったとき）。 */
    fun unregister(process: ManagedProcess) {
        entries.removeIf { it.second === process }
    }

    /** 生きている登録中のプロセス。 */
    fun live(): List<ManagedProcess> = entries.map { it.second }.filter { it.isAlive }

    /** シャットダウンフックでプロセスを止めた後に呼ぶ処理を足す（WP7 の LeaseRegistry.interrupt など）。 */
    fun addShutdownListener(listener: () -> Unit) {
        installHook()
        shutdownListeners.add(listener)
    }

    /** 生きているプロセスを種類の順に止め、登録された処理を呼ぶ。フックから 1 回だけ実行される。 */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        runBlocking {
            for (kind in Kind.entries) {
                // 同じ種類は順に止める（数は少なく、猶予は 1 つあたり 5 秒）
                entries.filter { it.first == kind && it.second.isAlive }.forEach { (_, process) ->
                    runCatching { process.stop(SHUTDOWN_GRACE) }
                }
            }
        }
        // 記録の確定などは、プロセスが止まってから行う
        shutdownListeners.forEach { listener -> runCatching { listener() } }
    }

    /** JVM のシャットダウンフックを 1 回だけ入れる。 */
    private fun installHook() {
        if (!hookInstalled.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(Thread(::shutdown, "fukurou-shutdown"))
    }

    /** シャットダウンでの 1 プロセスあたりの猶予。 */
    private val SHUTDOWN_GRACE = 5.seconds
}
