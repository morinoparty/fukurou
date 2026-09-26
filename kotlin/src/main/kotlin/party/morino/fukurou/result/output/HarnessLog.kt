package party.morino.fukurou.result.output

import java.io.BufferedWriter
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories

/**
 * fukurou 自身の出力を標準エラーと logs/harness.log の両方へ書く（run/harness_log.py）。
 *
 * SLF4J などには依存しない小さな書き出し先。1 サーバー（1 run ディレクトリ）に 1 つ。
 *
 * @param file harness.log の置き場。null なら標準エラーだけに書く（単体テスト用）
 * @param mirror コンソールの書き出し先（Gradle のテスト出力に出るよう標準エラー）
 * @param clock 時刻の取得元
 */
internal class HarnessLog(
    file: Path?,
    private val mirror: PrintStream? = System.err,
    private val clock: Clock = Clock.systemDefaultZone(),
) : AutoCloseable {
    /** ファイルへの書き出し先。実行ごとに作り直す（Python の mode="w" と同じ）。 */
    private val writer: BufferedWriter? = file?.let {
        it.parent?.createDirectories()
        Files.newBufferedWriter(it, Charsets.UTF_8)
    }

    /** 閉じた後の書き込みを無視するための印。 */
    private var closed = false

    /** 情報の行。 */
    fun info(message: String): Unit = log("INFO", message)

    /** 警告の行（遅れて届いた記録の無視など）。 */
    fun warn(message: String): Unit = log("WARNING", message)

    /** エラーの行。例外があればスタックトレースをファイルにだけ残す。 */
    fun error(message: String, error: Throwable? = null): Unit = log("ERROR", message, error)

    /** 1 行を書く。レーンのスレッドからも呼ばれるので直列にする。 */
    @Synchronized
    private fun log(level: String, message: String, error: Throwable? = null) {
        // コンソールは Python と同じ "[fukurou] <message>" の短い形
        mirror?.println("$CONSOLE_PREFIX$message")
        if (closed || writer == null) return
        // parallel のレーンからの行を見分けられるよう、ファイルにはスレッド名も残す
        val timestamp = TIMESTAMP.format(clock.instant().atZone(clock.zone))
        writer.write("$timestamp $level $LOGGER_NAME [${Thread.currentThread().name}]: $message")
        writer.newLine()
        error?.let { writer.write(it.stackTraceToString()) }
        // 途中でプロセスが殺されても、そこまでの行が残るよう毎回書き出す
        writer.flush()
    }

    /** ファイルを閉じる。冪等。 */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        writer?.close()
    }

    /** 書式の定数。 */
    private companion object {
        /** コンソールの行の接頭辞。 */
        private const val CONSOLE_PREFIX = "[fukurou] "

        /** ロガー名（Python のルートロガー名と同じ）。 */
        private const val LOGGER_NAME = "fukurou"

        /** Python logging の asctime と同じ形（2026-09-26 12:00:00,123）。 */
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")
    }
}
