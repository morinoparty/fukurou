package party.morino.fukurou.engine.process

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /** プロセスグループ id（groupKill なら pid と同じ）。読めなければ pid とみなす。 */
    val pgid: Long get() = if (groupKill) pid else ProcessLauncher.readPgrp(pid) ?: pid

    /** 生きているか。 */
    val isAlive: Boolean get() = process.isAlive

    /** 終了コード。まだ生きていれば null。 */
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    /** timeout まで終了を待つ。終了したら終了コード、時間切れなら null。キャンセルできる。 */
    suspend fun awaitExit(timeout: Duration): Int? =
        withTimeoutOrNull(timeout) { process.onExit().await().exitValue() }

    /** TERM → grace 待ち → KILL → 10 s 待ち（stop_process）。既に終了していれば何もしない。 */
    suspend fun stop(grace: Duration) {
        if (!process.isAlive) return
        // setsid が fork した場合に備え、シグナルを送る前に子孫を控えておく（終了後は辿れなくなる）
        val descendants = if (groupKill) emptyList() else process.descendants().toList()
        for ((signal, wait) in listOf("TERM" to grace, "KILL" to KILL_WAIT)) {
            if (groupKill) {
                // プロセスグループ全体（PortableMC → Java などの子孫も含む）へ送る
                val result = ExternalCommand.run(listOf("kill", "-$signal", "--", "-$pgid"))
                // グループがもう無い（ProcessLookupError）なら止め終わっている
                if (result.exit != 0 && !process.isAlive) return
            } else {
                // 子孫と本体へ直接送る（TERM は destroy、KILL は destroyForcibly）
                val targets = descendants + process.toHandle()
                targets.forEach { if (signal == "TERM") it.destroy() else it.destroyForcibly() }
            }
            if (awaitExit(wait) != null) return
        }
    }

    override fun toString(): String = "ManagedProcess($name, pid=$pid, groupKill=$groupKill)"

    private companion object {
        /** SIGKILL の後に待つ時間（runner/process.py:61）。 */
        val KILL_WAIT: Duration = 10.seconds
    }
}
