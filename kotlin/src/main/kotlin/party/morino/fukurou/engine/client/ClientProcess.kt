package party.morino.fukurou.engine.client

import party.morino.fukurou.version.MinecraftVersion
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.time.Duration

/**
 * 1 プレイヤーの Minecraft クライアント（PortableMC で起動する、runner/client.py:33-110）。
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
    /** このクライアントの logs/latest.log。 */
    val latestLog: Path get() = clientDir.resolve("logs").resolve("latest.log")

    /** F2 のスクリーンショットの保存先。 */
    val screenshotsDir: Path get() = clientDir.resolve("screenshots")

    /** クラッシュレポートの保存先。 */
    val crashReportsDir: Path get() = clientDir.resolve("crash-reports")

    /** 起動中か。 */
    val isAlive: Boolean get() = TODO("WP4: ClientProcess.isAlive")

    /** options.txt を書き、--dry でゲームのファイルを揃える（CacheLock の下、timeout で止める）。 */
    suspend fun install(timeout: Duration): Unit = TODO("WP4: ClientProcess.install($timeout)")

    /** display の上で起動し、joinAddress のサーバーへ接続させる。 */
    suspend fun start(display: String, joinAddress: InetSocketAddress): Unit =
        TODO("WP4: ClientProcess.start($display, $joinAddress)")

    /** 終了していれば launch.log の末尾 30 行つきの ClientDiedException。 */
    fun checkAlive(): Unit = TODO("WP4: ClientProcess.checkAlive")

    /** プロセスグループを止める。 */
    suspend fun stop(grace: Duration): Unit = TODO("WP4: ClientProcess.stop($grace)")
}
