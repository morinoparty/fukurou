package party.morino.fukurou.engine.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import party.morino.fukurou.engine.net.CacheLock
import party.morino.fukurou.engine.process.ManagedProcess
import party.morino.fukurou.engine.process.ProcessLauncher
import party.morino.fukurou.engine.process.ProcessRegistry
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.version.MinecraftVersion
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 1 プレイヤーの Minecraft クライアント（PortableMC で起動する、runner/client.py:33-110）。
 * Quick Play でテスト用サーバーへ直接参加する。
 *
 * PortableMC の作業ディレクトリは tools/（portablemc の 2 つ上）。インストール時の出力は launchLog と同じ場所の
 * <player>-install.log に分けて残す。
 *
 * @property player プレイヤー名
 * @property version 起動するバージョン
 * @property portablemc PortableMC の実行ファイル
 * @property mainDir 共有の Minecraft キャッシュ（<workDir>/cache/minecraft）
 * @property clientDir このプレイヤーの .minecraft（<workDir>/servers/<runId>/clients/<player>）
 * @property heap -Xmx
 * @property launchLog PortableMC の出力の書き出し先（<player>-launch.log）
 */
internal class ClientProcess(
    val player: String,
    val version: MinecraftVersion,
    val portablemc: Path,
    val mainDir: Path,
    val clientDir: Path,
    val heap: String,
    val launchLog: Path,
) {
    /** インストール（--dry）の出力の書き出し先。 */
    val installLog: Path get() = launchLog.resolveSibling("$player-install.log")

    /** PortableMC の作業ディレクトリ（<workDir>/tools）。 */
    private val toolsDir: Path get() = portablemc.toAbsolutePath().parent.parent

    /** 起動したクライアント。start の前は null。 */
    @Volatile
    private var process: ManagedProcess? = null

    /** PortableMC の引数。 */
    private val command: ClientLaunchCommand
        get() = ClientLaunchCommand(portablemc, mainDir, version.id, clientDir, heap, player)

    /** このクライアントの logs/latest.log。 */
    val latestLog: Path get() = clientDir.resolve("logs").resolve("latest.log")

    /** F2 のスクリーンショットの保存先。 */
    val screenshotsDir: Path get() = clientDir.resolve("screenshots")

    /** クラッシュレポートの保存先。 */
    val crashReportsDir: Path get() = clientDir.resolve("crash-reports")

    /** 起動中か。start の前は false。 */
    val isAlive: Boolean get() = process?.isAlive == true

    /** options.txt を書き、--dry でゲームのファイルを揃える（CacheLock の下、timeout で止める）。 */
    suspend fun install(timeout: Duration) {
        runInterruptible(Dispatchers.IO) {
            Files.createDirectories(clientDir)
            // 初回起動の案内やポーズを抑える設定を、ゲームのファイルより先に置く
            Files.writeString(clientDir.resolve("options.txt"), ClientOptions.TEXT)
        }
        // 同じバージョンのダウンロードを他のプレイヤーや JVM と重ねない
        CacheLock.withLock(mainDir.resolve(".install-${version.id}")) {
            val installer = launch("$player client installer", command.install(), installLog, emptyMap())
            val code = try {
                installer.awaitExit(timeout)
            } catch (error: CancellationException) {
                // 中断でも止める。別セッションで起動しているためシグナルは届かず、後片付けからも見えない
                withContext(NonCancellable) { stopQuietly(installer) }
                throw error
            }
            if (code == null) {
                withContext(NonCancellable) { stopQuietly(installer) }
                throw SetupException("$player: client installation timed out after ${timeout.inWholeSeconds}s; see $installLog")
            }
            ProcessRegistry.unregister(installer)
            if (code != 0) throw SetupException("$player: client installation failed with code $code; see $installLog")
        }
    }

    /** display の上で起動し、joinAddress のサーバーへ接続させる。 */
    suspend fun start(display: String, joinAddress: InetSocketAddress) {
        val env = buildMap {
            put("DISPLAY", display)
            // GPU の無い CI では Mesa のソフトウェアレンダリングを使う（利用者が明示した値は尊重する）
            if (System.getenv("LIBGL_ALWAYS_SOFTWARE") == null) put("LIBGL_ALWAYS_SOFTWARE", "true")
        }
        process = launch("$player client", command.launch(joinAddress), launchLog, env)
    }

    /** 終了していれば launch.log の末尾 30 行つきの ClientDiedException。start の前は何もしない。 */
    fun checkAlive() {
        val current = process ?: return
        val code = current.exitCode ?: return
        throw ClientDiedException(player, "$player: client exited with code $code" + tail())
    }

    /** プロセスグループを止める。 */
    suspend fun stop(grace: Duration) {
        val current = process ?: return
        current.stop(grace)
        ProcessRegistry.unregister(current)
    }

    /** setsid で起動し、シャットダウンフックの対象にする。ProcessLauncher は少し待つので IO で呼ぶ。 */
    private suspend fun launch(name: String, argv: List<String>, log: Path, env: Map<String, String>): ManagedProcess {
        val launched = runInterruptible(Dispatchers.IO) { ProcessLauncher.launch(name, argv, toolsDir, log, env) }
        ProcessRegistry.register(ProcessRegistry.Kind.CLIENT, launched)
        return launched
    }

    /** インストーラーを短い猶予で止める（失敗しても元の例外を優先する）。 */
    private suspend fun stopQuietly(installer: ManagedProcess) {
        runCatching { installer.stop(INSTALLER_GRACE) }
        ProcessRegistry.unregister(installer)
    }

    /** launch.log の末尾。読めなければ空。 */
    private fun tail(): String {
        // 壊れた文字があっても読めるよう、バイト列から置換つきで文字列にする
        val text = runCatching { String(Files.readAllBytes(launchLog), Charsets.UTF_8) }.getOrNull() ?: return ""
        val lines = text.trimEnd().lines().takeIf { text.isNotBlank() } ?: return ""
        return "\nlast ${minOf(lines.size, TAIL_LINES)} lines of $launchLog:\n" + lines.takeLast(TAIL_LINES).joinToString("\n")
    }

    private companion object {
        /** インストーラーを止めるときの猶予（client.py:79）。 */
        val INSTALLER_GRACE: Duration = 5.seconds

        /** ClientDiedException に添える launch.log の行数。 */
        const val TAIL_LINES: Int = 30
    }
}
