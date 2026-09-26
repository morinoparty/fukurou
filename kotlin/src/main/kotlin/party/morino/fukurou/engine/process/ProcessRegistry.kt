package party.morino.fukurou.engine.process

import kotlinx.coroutines.runBlocking
import party.morino.fukurou.error.SetupException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * 生きている外部プロセスの一覧と、JVM に 1 つだけのシャットダウンフック（cli.py:305,348）。
 *
 * Gradle のキャンセルや SIGTERM で JVM が終わるとき、まず止める前の処理（実行中のテストを interrupted として記録する）を呼び、
 * 次にクライアント → Xvfb → サーバーの順に 5 秒の猶予で止め、最後に止めた後の処理（ログの回収と result.json の確定）を呼ぶ。
 *
 * シャットダウンフックの間も JUnit のスレッドは動き続ける。先にプロセスを止めると、実行中のステップがサーバーや
 * クライアントの死亡を見て、中断ではなく「サーバーが死んだ」と記録してしまう（Python は KeyboardInterrupt が先に届く）。
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

    /** プロセスを止める前に呼ぶ処理。 */
    private val beforeStopListeners = CopyOnWriteArrayList<() -> Unit>()

    /** プロセスを止めた後に呼ぶ処理。 */
    private val afterStopListeners = CopyOnWriteArrayList<() -> Unit>()

    /** フックを入れたか。 */
    private val hookInstalled = AtomicBoolean(false)

    /** シャットダウンの処理を始めたか（2 回実行しない）。 */
    private val shuttingDown = AtomicBoolean(false)

    /** JVM のシャットダウンを始めたか。エンジンはこれを見て、プロセスの死亡を中断として扱い、新しく起動しない。 */
    val isShuttingDown: Boolean get() = shuttingDown.get()

    /**
     * process を登録する。最初の登録でシャットダウンフックを入れる。
     *
     * シャットダウンが始まった後の登録（フックと同時に走ったクライアントの再起動など）は、フックが種類ごとの一覧を
     * たどり終えた後かもしれず、JVM より長く生き残りうるので、その場で止めて SetupException を投げる。
     */
    fun register(kind: Kind, process: ManagedProcess) {
        if (shuttingDown.get()) {
            runBlocking { runCatching { process.stop(SHUTDOWN_GRACE) } }
            throw SetupException("${process.name} was started while the JVM is shutting down; it was stopped")
        }
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

    /**
     * シャットダウンフックで呼ぶ処理を足す。
     *
     * @param beforeStop プロセスを止める前（実行中のテストを interrupted にし、run の失敗を記録する）
     * @param afterStop プロセスを止めた後（ログを回収し、result.json を確定する）
     */
    fun addShutdownListener(beforeStop: () -> Unit, afterStop: () -> Unit) {
        installHook()
        beforeStopListeners.add(beforeStop)
        afterStopListeners.add(afterStop)
    }

    /** 止める前の処理、生きているプロセスを種類の順に止める、止めた後の処理、の順に行う。フックから 1 回だけ実行される。 */
    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        // プロセスが死ぬより前に中断を記録し、JUnit のスレッドが見る死亡を中断として扱わせる
        beforeStopListeners.forEach { listener -> runCatching { listener() } }
        runBlocking {
            for (kind in Kind.entries) {
                // 同じ種類は順に止める（数は少なく、猶予は 1 つあたり 5 秒）
                entries.filter { it.first == kind && it.second.isAlive }.forEach { (_, process) ->
                    runCatching { process.stop(SHUTDOWN_GRACE) }
                }
            }
        }
        // ログの回収と記録の確定は、プロセスが止まってから行う
        afterStopListeners.forEach { listener -> runCatching { listener() } }
    }

    /** JVM のシャットダウンフックを 1 回だけ入れる。 */
    private fun installHook() {
        if (!hookInstalled.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(Thread(::shutdown, "fukurou-shutdown"))
    }

    /** シャットダウンでの 1 プロセスあたりの猶予。 */
    private val SHUTDOWN_GRACE = 5.seconds
}
