package party.morino.fukurou.engine.boot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import party.morino.fukurou.engine.log.LogWindow
import party.morino.fukurou.engine.net.CacheLock
import party.morino.fukurou.engine.process.ManagedProcess
import party.morino.fukurou.engine.process.ProcessLauncher
import party.morino.fukurou.engine.process.ProcessRegistry
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.spi.CommandChannel
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.spi.model.ProvisionRequest
import party.morino.fukurou.spi.model.Provisioned
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 1 セッション分のサーバーのプロセスとコマンド経路（server/process.py の ServerProcess、session.py:84 start の移植）。
 *
 * 起動・準備完了の待ち・停止だけを受け持ち、何を送るか（Paper の "list" / "stop" など）は ServerPlatform が決める。
 *
 * @property provisioned 種類が用意した起動方法
 * @property process サーバーのプロセス（プロセスグループごと止める）
 * @property channel コマンド経路（無い種類は null）
 * @property logFile コンソールの記録（artifact の logs/sessions/<n>/server.log に直接書く）
 */
internal class ServerBoot(
    val provisioned: Provisioned,
    val process: ManagedProcess,
    val channel: CommandChannel?,
    val logFile: Path,
) {
    /**
     * 穏やかに止める: 種類の停止要求 → 60 秒待つ → プロセスグループを TERM（20 秒）→ KILL（server/process.py:103）。
     */
    suspend fun stop(platform: ServerPlatform) {
        try {
            if (process.isAlive) {
                // 停止の要求が届かなくても（既に落ちている等）、プロセスは下で止める
                runCatching { platform.requestStop(channel) }
                if (process.awaitExit(STOP_WAIT) == null) process.stop(STOP_GRACE)
            }
        } finally {
            ProcessRegistry.unregister(process)
            runCatching { channel?.close() }
        }
    }

    /** 起動に失敗したときの後始末。 */
    private suspend fun kill() {
        withContext(NonCancellable) {
            runCatching { process.stop(STOP_GRACE) }
            ProcessRegistry.unregister(process)
            runCatching { channel?.close() }
        }
    }

    /** 起動の手順と定数。 */
    companion object {
        /** 停止の要求の後に待つ時間（server/process.py:103）。 */
        private val STOP_WAIT: Duration = 60.seconds

        /** TERM の後に待つ時間。 */
        private val STOP_GRACE: Duration = 20.seconds

        /** 準備完了を確かめる間隔（server/process.py:72-84）。 */
        private val POLL: Duration = 1.seconds

        /** 起動に失敗したときに harness.log へ出す末尾の行数（runner/process.py:28 tail_log）。 */
        private const val TAIL_LINES = 30

        /**
         * provision → 起動 → 準備完了（readyPattern + confirmReady）まで待つ。ポートの取り合いで失敗したら 1 回だけやり直す。
         *
         * @param name ログとエラーに使うサーバーの名前（result id）
         * @param request provision の依頼（サーバーディレクトリは呼び出し側が空にする）
         * @param prepare provision の前に毎回呼ぶ（サーバーディレクトリを空にする）
         * @param logFile コンソールの記録の置き場
         * @param artifactPath logFile の artifact 内のパス（メッセージ用）
         * @param timeout 準備完了を待つ時間
         * @param warn harness.log への警告
         * @throws ServerUnavailableException 準備完了の前にプロセスが終わった
         * @throws HarnessTimeoutException 期限までに準備完了にならなかった
         */
        suspend fun boot(
            platform: ServerPlatform,
            name: String,
            request: ProvisionRequest,
            prepare: () -> Unit,
            logFile: Path,
            artifactPath: String,
            timeout: Duration,
            warn: (String) -> Unit,
        ): ServerBoot {
            repeat(ATTEMPTS) { attempt ->
                prepare()
                val provisioned = platform.provision(request)
                // 同じ版の Paper の展開（bundler）は同時に行わない。ロックは準備完了か失敗まで持つ
                val launch: suspend () -> ServerBoot? = { launch(platform, name, provisioned, logFile, artifactPath, timeout, warn) }
                val lock = provisioned.bundlerLock
                val booted = if (lock != null) CacheLock.withLock(lock) { launch() } else launch()
                if (booted != null) return booted
                warn("$name could not bind its ports (attempt ${attempt + 1}); retrying with new ports")
            }
            throw ServerUnavailableException("the server ($name) could not bind its ports after $ATTEMPTS attempts; see $artifactPath")
        }

        /** ポートの取り合いでのやり直しを含めた試行回数。 */
        private const val ATTEMPTS = 2

        /** 起動して準備完了まで待つ。ポートの取り合いなら null。 */
        private suspend fun launch(
            platform: ServerPlatform,
            name: String,
            provisioned: Provisioned,
            logFile: Path,
            artifactPath: String,
            timeout: Duration,
            warn: (String) -> Unit,
        ): ServerBoot? {
            val process = runInterruptible(Dispatchers.IO) {
                ProcessLauncher.launch("$name server", provisioned.command, provisioned.workingDir, logFile, provisioned.environment, warn)
            }
            ProcessRegistry.register(ProcessRegistry.Kind.SERVER, process)
            val booted = ServerBoot(provisioned, process, platform.openChannel(provisioned), logFile)
            try {
                // 印を付けない窓なので、起動からの全文を読む
                val window = LogWindow(logFile)
                val deadline = TimeSource.Monotonic.markNow() + timeout
                var sawReady = false
                while (true) {
                    val text = runInterruptible(Dispatchers.IO) { window.read() }
                    if (platform.portConflictPattern?.containsMatchIn(text) == true) {
                        booted.kill()
                        return null
                    }
                    if (!sawReady && platform.readyPattern.containsMatchIn(text)) sawReady = true
                    // 起動完了の行の直後は RCON がまだ応答しないことがあるので、確認できるまで繰り返す
                    if (sawReady && platform.confirmReady(booted.channel)) return booted
                    process.exitCode?.let { code ->
                        warn("server output (last lines):\n${tail(logFile)}")
                        throw ServerUnavailableException("the server ($name) exited with code $code before it was ready; see $artifactPath")
                    }
                    if (deadline.hasPassedNow()) {
                        throw HarnessTimeoutException("the server ($name) did not become ready within ${timeout.inWholeSeconds} seconds; see $artifactPath")
                    }
                    delay(POLL)
                }
            } catch (error: Throwable) {
                booted.kill()
                throw error
            }
        }

        /** ログの末尾（runner/process.py:28 tail_log）。 */
        private fun tail(logFile: Path): String =
            runCatching { String(Files.readAllBytes(logFile), Charsets.UTF_8).trimEnd().lines().takeLast(TAIL_LINES).joinToString("\n") }
                .getOrDefault("(no output)")
    }
}
