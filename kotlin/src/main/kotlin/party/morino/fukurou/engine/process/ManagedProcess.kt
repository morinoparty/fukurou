package party.morino.fukurou.engine.process

import java.nio.file.Path
import kotlin.time.Duration

/**
 * setsid で起動した長寿命のプロセス（サーバー・クライアント・Xvfb）。runner/process.py:40-70 の移植。
 *
 * 止めるときはプロセスグループ全体へ kill -TERM / -KILL -- -<pgid> を送る。
 * pid と pgid が一致しない（setsid が fork した）場合は、子孫のスナップショットを直接止める。
 *
 * @property name ハーネスログに出す名前（"server"、"Alice client" など）
 * @property process 起動したプロセス（setsid の子）
 * @property logFile 標準出力と標準エラーの書き出し先
 * @property groupKill プロセスグループへのシグナルで止められるか（pid == pgid）
 */
internal class ManagedProcess(
    val name: String,
    val process: Process,
    val logFile: Path,
    val groupKill: Boolean,
) {
    /** プロセス id。 */
    val pid: Long get() = process.pid()

    /** プロセスグループ id（groupKill なら pid と同じ）。 */
    val pgid: Long get() = TODO("WP2: ManagedProcess.pgid")

    /** 生きているか。 */
    val isAlive: Boolean get() = TODO("WP2: ManagedProcess.isAlive")

    /** 終了コード。まだ生きていれば null。 */
    val exitCode: Int? get() = TODO("WP2: ManagedProcess.exitCode")

    /** timeout まで終了を待つ。終了したら終了コード、時間切れなら null。キャンセルできる。 */
    suspend fun awaitExit(timeout: Duration): Int? = TODO("WP2: ManagedProcess.awaitExit($timeout)")

    /** TERM → grace 待ち → KILL → 10 s 待ち（stop_process）。既に終了していれば何もしない。 */
    suspend fun stop(grace: Duration): Unit = TODO("WP2: ManagedProcess.stop($grace)")
}
