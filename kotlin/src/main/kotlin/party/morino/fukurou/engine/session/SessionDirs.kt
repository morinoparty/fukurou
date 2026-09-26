package party.morino.fukurou.engine.session

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory

/**
 * 1 サーバー（1 run）ごとの作業ディレクトリ（run/suite_run.py:143-155,520-525）。
 *
 * Python は work-dir 全体の server / clients / logs を実行ごとに消すが（_reset_work_dir）、
 * Kotlin 版は 1 JVM で複数のサーバーを動かすので、run ごとに servers/<runId>/ を分けて全体は消さない。
 * tools/ と cache/ は全サーバーで共有する（ロックで守る）。
 *
 * @property workDir fukurou の作業ディレクトリ
 * @property runId run の id
 */
internal class SessionDirs(val workDir: Path, val runId: String) {
    /** この run の作業ディレクトリ。 */
    val root: Path = workDir.resolve("servers").resolve(runId)

    /** サーバーのディレクトリ。provision の前に毎回消す。 */
    val serverDir: Path get() = root.resolve("server")

    /** Xvfb とクライアントの起動のログ（artifact には入れない）。 */
    val logsDir: Path get() = root.resolve("logs")

    /** portablemc などの道具（全サーバーで共有）。 */
    val toolsDir: Path get() = workDir.resolve("tools")

    /** クライアントのバージョン・アセット（全サーバーで共有）。 */
    val minecraftCache: Path get() = workDir.resolve("cache").resolve("minecraft")

    /** プレイヤーごとのクライアントの設定・ログ・スクリーンショット。 */
    fun clientDir(player: String): Path = root.resolve("clients").resolve(player)

    /** プレイヤーの Xvfb のログ。 */
    fun xvfbLog(player: String): Path = logsDir.resolve("$player-xvfb.log")

    /** プレイヤーのクライアントの起動のログ。 */
    fun launchLog(player: String): Path = logsDir.resolve("$player-launch.log")

    /** サーバーのディレクトリを空にする（前のセッションのワールドを残さない）。 */
    @OptIn(ExperimentalPathApi::class)
    fun wipeServerDir() {
        if (serverDir.isDirectory()) serverDir.deleteRecursively()
        Files.createDirectories(serverDir)
    }

    /** run の作業ディレクトリを消す（keepWork でなければ close のときに呼ぶ）。 */
    @OptIn(ExperimentalPathApi::class)
    fun delete() {
        if (root.isDirectory()) root.deleteRecursively()
    }
}
