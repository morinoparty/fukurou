package party.morino.fukurou.result.output

import party.morino.fukurou.error.SetupException
import party.morino.fukurou.result.model.session.LogInfo
import party.morino.fukurou.result.model.session.LogKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 1 サーバー分の run ディレクトリ（<outDir>/<runId>/）の配置（run/artifacts.py、契約の §1）。
 *
 * パスは run ディレクトリからの相対パス（/ 区切り）で result.json に書く。
 * サーバーやクライアントの本体・アセット・ワールドは含めない。
 *
 * @property outDir artifact のルート（run ディレクトリの親）
 * @property runId run の id（run ディレクトリ名）
 */
internal class ArtifactLayout(val outDir: Path, val runId: String) {
    /** この run の出力の置き場。 */
    val runDir: Path = outDir.resolve(checkRelative(runId))

    /** result.json の置き場。 */
    val resultFile: Path get() = runDir.resolve(RESULT_FILE)

    /** harness.log の置き場。 */
    val harnessLogFile: Path get() = runDir.resolve(HARNESS_LOG)

    /** 既に回収したクラッシュレポート。fresh-server の切り替え時に前のセッションの分を重ねて載せない。 */
    private val collectedReports = mutableSetOf<Path>()

    /**
     * run ディレクトリを用意する。fukurou の印がある既存の出力は消し、印の無いディレクトリは消さずに止める。
     *
     * JVM 内で 2 回目以降に呼ばないこと（同じ run の途中の出力を消してしまう）。
     *
     * @throws SetupException 印の無い run ディレクトリに契約の項目が既にある
     */
    fun prepare() {
        runDir.createDirectories()
        val marker = runDir.resolve(OUT_DIR_MARKER)
        if (!marker.exists()) {
            // 印が無いのに logs/ などがあるなら利用者のものかもしれないので消さない（--out-dir . の誤指定など）
            val foreign = OUTPUT_ENTRIES.filter { runDir.resolve(it).isDirectory() }
            if (foreign.isNotEmpty()) {
                throw SetupException(
                    "refusing to delete ${foreign.joinToString(", ")} in $runDir: the directory was not created by " +
                        "fukurou; choose an empty or new fukurou.outDir",
                )
            }
            Files.createFile(marker)
        }
        // run ディレクトリ自体は残し、fukurou が書く項目だけを消す
        OUTPUT_ENTRIES.forEach { deleteTree(runDir.resolve(it)) }
    }

    /** run ディレクトリからの相対パスを実際のパスにする。 */
    fun path(relative: String): Path = runDir.resolve(checkRelative(relative))

    /**
     * run ディレクトリの中のパスを、result.json に書く相対パス（/ 区切り）にする。
     *
     * @throws IllegalArgumentException run ディレクトリの外のパス
     */
    fun relative(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        val root = runDir.toAbsolutePath().normalize()
        // run ディレクトリの外を指すパスを書くと、ビューアが artifact の外を読もうとする
        require(absolute.startsWith(root) && absolute != root) { "$path is not inside the run directory $root" }
        return checkRelative(root.relativize(absolute).invariantSeparatorsPathString)
    }

    /** テストのスクリーンショットの相対パス（tests/<id>/screenshots/<player>/<name>.png）。 */
    fun screenshot(testId: String, player: String, name: String): String = checkRelative("$TESTS_DIR/$testId/screenshots/$player/$name.png")

    /** 失敗時に全プレイヤーから撮るスクリーンショットの相対パス。 */
    fun failureScreenshot(testId: String, player: String): String = screenshot(testId, player, "failure")

    /** harness.log の LogInfo。 */
    fun harnessLog(): LogInfo = LogInfo(LogKind.HARNESS, HARNESS_LOG)

    /** セッション n のサーバーコンソールの記録をコピーする。元が無ければ null。 */
    fun collectServerLog(index: Int, source: Path): LogInfo? {
        val relative = sessionServerLog(index)
        return copyIfExists(source, relative, LogInfo(LogKind.SERVER, relative))
    }

    /** クライアントの latest.log を、セッションと起動回数に応じた名前でコピーする。元が無ければ null。 */
    fun collectClientLog(index: Int, player: String, launch: Int, source: Path): LogInfo? {
        val relative = sessionClientLog(index, player, launch)
        return copyIfExists(source, relative, LogInfo(LogKind.CLIENT, relative, player))
    }

    /** クライアントがクラッシュしたときのレポートをプレイヤーごとのディレクトリにコピーする（新しいものだけ）。 */
    @Synchronized
    fun collectCrashReports(player: String, crashDir: Path): List<LogInfo> {
        if (!crashDir.isDirectory()) return emptyList()
        return crashDir.listDirectoryEntries("*.txt").sorted().mapNotNull { report ->
            // 同じレポートを 2 回載せない（再起動しても crash-reports/ は残るため）
            if (!collectedReports.add(report)) return@mapNotNull null
            val relative = crashReport(player, report.name)
            copy(report, relative)
            LogInfo(LogKind.CRASH, relative, player)
        }
    }

    /** 元のファイルがあればコピーして info を返す。 */
    private fun copyIfExists(source: Path, relative: String, info: LogInfo): LogInfo? {
        if (!source.isRegularFile()) return null
        copy(source, relative)
        return info
    }

    /** run ディレクトリの相対パスへコピーする（親ディレクトリも作る）。 */
    private fun copy(source: Path, relative: String) {
        val destination = path(relative)
        destination.parent.createDirectories()
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 契約の配置の定数と、パスの検査。 */
    companion object {
        /** result.json のファイル名。 */
        const val RESULT_FILE: String = "result.json"

        /** harness.log の相対パス。 */
        const val HARNESS_LOG: String = "logs/harness.log"

        /** テストごとの出力の置き場。 */
        const val TESTS_DIR: String = "tests"

        /** 前回の実行の出力が混ざらないよう、実行前に消す run ディレクトリ内の項目。 */
        val OUTPUT_ENTRIES: List<String> = listOf(RESULT_FILE, TESTS_DIR, "logs", "crash-reports")

        /** fukurou が作った run ディレクトリであることを示す印。 */
        const val OUT_DIR_MARKER: String = ".fukurou-out"

        /** セッション n のサーバーコンソールの記録の相対パス。 */
        fun sessionServerLog(index: Int): String = "logs/sessions/$index/server.log"

        /** セッション n のクライアントのログの相対パス。再起動 k 回目（k >= 2）は <player>.<k>.log。 */
        fun sessionClientLog(index: Int, player: String, launch: Int): String {
            val suffix = if (launch <= 1) "" else ".$launch"
            return checkRelative("logs/sessions/$index/clients/$player$suffix.log")
        }

        /** クラッシュレポートの相対パス。 */
        fun crashReport(player: String, fileName: String): String = checkRelative("crash-reports/$player/$fileName")

        /**
         * result.json に書く相対パスを検査する。
         *
         * @throws IllegalArgumentException 空・絶対パス・"\\"・".." を含む
         */
        fun checkRelative(relative: String): String {
            require(relative.isNotEmpty()) { "an artifact path must not be empty" }
            // ビューアは / 区切りの相対パスとして読むので、Windows の区切りやドライブ名は受け付けない
            require('\\' !in relative) { "artifact path '$relative' must use '/' separators" }
            require(!relative.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(relative)) {
                "artifact path '$relative' must be relative"
            }
            // ".." で run ディレクトリの外へ出るパスを書かせない
            require(relative.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
                "artifact path '$relative' must not contain '..', '.' or empty segments"
            }
            return relative
        }

        /**
         * この JVM で計画していない、fukurou の印がある run ディレクトリを消す（古い run を artifact に混ぜない）。
         *
         * @param outDir artifact のルート
         * @param keep この JVM で計画した run の id
         * @return 消した run の id
         */
        fun pruneStale(outDir: Path, keep: Set<String>): List<String> {
            if (!outDir.isDirectory()) return emptyList()
            return outDir.listDirectoryEntries().sorted().filter { dir ->
                // 印の無いディレクトリは利用者のものかもしれないので触らない
                dir.isDirectory() && dir.name !in keep && dir.resolve(OUT_DIR_MARKER).exists()
            }.map { dir ->
                deleteTree(dir)
                dir.name
            }
        }

        /** ファイルかディレクトリを消す（無ければ何もしない）。 */
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        private fun deleteTree(path: Path) {
            if (path.isDirectory()) path.deleteRecursively() else path.deleteIfExists()
        }
    }
}
