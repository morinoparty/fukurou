package party.morino.fukurou.engine.process

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import party.morino.fukurou.error.SetupException

/** setsid で新しいセッション（プロセスグループ）として起動する（start_new_session 相当）。 */
internal object ProcessLauncher {
    /**
     * argv を setsid 越しに起動する。標準入力は /dev/null、標準出力と標準エラーは logFile（先に空にする）。
     *
     * setsid には --wait を付ける。fork しない通常の経路では何も変わらず、fork した場合も setsid が子の終了を待つので、
     * 生存確認と子孫のたどりが正しいまま保たれる。
     * /proc/<pid>/stat の pgrp が pid と違えば warn に書き、子孫を直接止める方式にする。
     *
     * @param name ハーネスログに出す名前
     * @param argv setsid を除いたコマンド
     * @param cwd 作業ディレクトリ
     * @param logFile 出力の書き出し先
     * @param env 追加の環境変数
     * @param warn 警告の書き出し先（harness.log）
     */
    fun launch(
        name: String,
        argv: List<String>,
        cwd: Path,
        logFile: Path,
        env: Map<String, String> = emptyMap(),
        warn: (String) -> Unit = {},
    ): ManagedProcess {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        // ログと作業ディレクトリの置き場所を先に作る（start_process と同じ）
        Files.createDirectories(logFile.toAbsolutePath().parent)
        Files.createDirectories(cwd)
        val builder = ProcessBuilder(listOf("setsid", "--wait") + argv)
            .directory(cwd.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectErrorStream(true)
            // Redirect.to はファイルを空にしてから書く（Python の "wb" と同じ）
            .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
        builder.environment().putAll(env)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw SetupException("could not start $name (${argv.first()}): ${error.message}", error)
        }
        // setsid が fork せずに exec していれば pid == pgid。違えばグループへのシグナルは使えない
        val pgrp = awaitSessionLeader(process)
        val groupKill = pgrp == process.pid()
        if (!groupKill) {
            warn("$name: pid ${process.pid()} is not its process group leader (pgrp=$pgrp); stopping its descendants directly")
        }
        return ManagedProcess(name, process, logFile, groupKill)
    }

    /**
     * setsid() が呼ばれるまで少し待って pgrp を返す。
     *
     * start() は setsid の exec が済んだ時点で戻るので、直後はまだ JVM のプロセスグループにいることがある。
     */
    private fun awaitSessionLeader(process: Process): Long? {
        val deadline = System.nanoTime() + SESSION_WAIT_NANOS
        var pgrp = readPgrp(process.pid())
        // pid と一致するか、プロセスが終わるか、待ち時間を過ぎるまで読み直す
        while (pgrp != process.pid() && process.isAlive && System.nanoTime() < deadline) {
            Thread.sleep(5)
            pgrp = readPgrp(process.pid())
        }
        return pgrp
    }

    /** setsid() を待つ最大の時間（2 秒）。 */
    private const val SESSION_WAIT_NANOS = 2_000_000_000L

    /**
     * /proc/<pid>/stat の 5 番目の項目（pgrp）を読む。読めなければ null。
     *
     * 2 番目の項目（comm）は空白や括弧を含みうるので、最後の ")" より後ろを区切る。
     */
    fun readPgrp(pid: Long): Long? = runCatching {
        parsePgrp(Files.readString(Path.of("/proc/$pid/stat")))
    }.getOrNull()

    /** stat の 1 行から pgrp を取り出す（")" の後ろは state ppid pgrp の順）。 */
    fun parsePgrp(stat: String): Long? {
        val rest = stat.substringAfterLast(')', missingDelimiterValue = "").trim()
        return rest.split(' ').getOrNull(2)?.toLongOrNull()
    }
}
