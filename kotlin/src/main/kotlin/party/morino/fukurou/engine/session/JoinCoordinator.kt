package party.morino.fukurou.engine.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import party.morino.fukurou.engine.client.StartupFailureDetector
import party.morino.fukurou.engine.log.LogWindow
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.SetupException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * クライアントを起動し、サーバーログに参加の行が出るまで待つ（run/session.py:99-141 join の移植）。
 *
 * 同時に起動すると CPU を取り合って読み込みが大幅に遅くなるので、JVM 全体で 1 台ずつ起動する（サーバーをまたいでも）。
 */
internal object JoinCoordinator {
    /** JVM 全体で 1 台ずつ起動するための公平なゲート。 */
    private val GATE = Semaphore(1)

    /** 参加の行を確かめる間隔（session.py:32）。 */
    private val POLL: Duration = 1.seconds

    /**
     * player を server に参加させる。
     *
     * @throws party.morino.fukurou.error.ClientDiedException クライアントが途中で終わった
     * @throws SetupException クライアントの描画を始められない（lavapipe が無いなど）
     * @throws party.morino.fukurou.error.ServerUnavailableException サーバーが途中で終わった
     * @throws HarnessTimeoutException 期限までに参加しなかった
     */
    suspend fun join(server: ServerInstance, player: PlayerSession) {
        player.ensureInstalled()
        GATE.withPermit {
            val name = player.name
            // 同じセッションでの再参加でも前回の参加の行に一致しないよう、起動前の位置から見る
            val probe = LogWindow(server.serverLogFile).also { it.mark() }
            // クライアント自身のログ。描画バックエンドを作れず止まった等を早めに見つける（起動で latest.log は作り直される）
            val clientLog = LogWindow(player.client.latestLog).also { it.mark() }
            server.log("$name: starting the client")
            player.launch(server.joinAddress)
            val joined = server.platform.joinedPattern(name)
            val timeout = server.definition.joinTimeout
            val deadline = TimeSource.Monotonic.markNow() + timeout
            while (!joined.containsMatchIn(runInterruptible(Dispatchers.IO) { probe.read() })) {
                if (deadline.hasPassedNow()) throw HarnessTimeoutException("$name did not join ${server.resultId} within ${timeout.inWholeSeconds} seconds")
                // 死んだクライアントは最後の行つきの ClientDiedException になる
                player.client.checkAlive()
                StartupFailureDetector.detect(runInterruptible(Dispatchers.IO) { clientLog.read() })?.let { reason ->
                    throw SetupException(
                        "$name's client cannot start: $reason — is mesa-vulkan-drivers (lavapipe) installed? " +
                            "morinoparty/fukurou/setup installs it.",
                    )
                }
                server.checkServerAlive()
                delay(POLL)
            }
            server.log("$name joined")
            player.joined()
            server.playerJoined(player)
        }
    }
}
