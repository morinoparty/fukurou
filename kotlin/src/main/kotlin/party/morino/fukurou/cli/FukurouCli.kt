package party.morino.fukurou.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import party.morino.fukurou.engine.net.MojangApi
import party.morino.fukurou.engine.paper.PaperApi
import party.morino.fukurou.error.FukurouException
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * fukurou のコマンドライン（v3 設計 V9）。
 *
 * ```text
 * versions <spec> [--max-versions N] [--paper-channel stable|beta|alpha] [--output FILE]
 * ```
 *
 * 終了コードは 0 成功 / 1 失敗（通信など）/ 2 入力の誤り。メッセージは標準エラーに `fukurou: …` で出す。
 * Gradle からは `./gradlew -q fukurouCli --args="versions 1.21.6- --output /tmp/m.json"` で動かす。
 */
internal object FukurouCli {
    /** 成功。 */
    const val EXIT_OK: Int = 0

    /** 失敗・エラー（通信の失敗など）。 */
    const val EXIT_FAILED: Int = 1

    /** 入力の誤り。 */
    const val EXIT_INVALID: Int = 2

    /** 使い方。 */
    private const val USAGE =
        "usage: fukurou versions <spec> [--max-versions N] [--paper-channel stable|beta|alpha] [--output FILE]"

    /** JVM の入口。 */
    @JvmStatic
    fun main(args: Array<String>) {
        val code = runBlocking { run(args.toList(), System.out, System.err) }
        System.out.flush()
        exitProcess(code)
    }

    /**
     * 引数を解釈して実行し、終了コードを返す。単体テストでは API を差し替える。
     *
     * @param out JSON を出す先（--output が無いとき）
     * @param err メッセージを出す先
     * @param mojang Mojang のマニフェスト
     * @param paper Paper の API
     */
    suspend fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
        mojang: MojangApi = MojangApi(),
        paper: PaperApi = PaperApi(),
    ): Int {
        if (args.isEmpty() || args.first() in HELP) {
            (if (args.isEmpty()) err else out).println(USAGE)
            return if (args.isEmpty()) EXIT_INVALID else EXIT_OK
        }
        return when (val command = args.first()) {
            "versions" -> versions(args.drop(1), out, err, mojang, paper)
            else -> fail(err, EXIT_INVALID, "unknown command '$command'\n$USAGE")
        }
    }

    /** versions サブコマンド。 */
    private suspend fun versions(args: List<String>, out: PrintStream, err: PrintStream, mojang: MojangApi, paper: PaperApi): Int {
        val options = try {
            VersionsOptions.parse(args)
        } catch (error: UsageError) {
            return fail(err, EXIT_INVALID, "${error.message}\n$USAGE")
        }
        if (options.help) {
            out.println(USAGE)
            return EXIT_OK
        }
        if (options.maxVersions < 1) return fail(err, EXIT_INVALID, "--max-versions must be at least 1")
        val versions = try {
            // 不正なチャンネルは通信する前に弾く
            VersionSelection.parseChannel(options.paperChannel)
            val releases = mojang.releaseIds()
            val paperVersions = paper.versionIds()
            VersionSelection.select(
                options.spec,
                releases,
                paperVersions,
                { version, threshold -> paper.hasAcceptedBuild(version, threshold) },
                options.maxVersions,
                options.paperChannel,
            )
        } catch (error: VersionError) {
            return fail(err, EXIT_INVALID, "invalid version spec: ${error.message}")
        } catch (error: FukurouException) {
            return fail(err, EXIT_FAILED, "could not resolve versions: ${error.message}")
        } catch (error: RuntimeException) {
            // 想定していない失敗（応答の形の違いなど）も通信の失敗と同じく 1 にする
            return fail(err, EXIT_FAILED, "could not resolve versions: $error")
        }
        // 1 行のコンパクトな JSON の配列（古い順）。アクションはそのまま matrix に使う
        val json = JsonArray(versions.map(::JsonPrimitive)).toString()
        val output = options.output
        if (output == null) {
            out.println(json)
        } else {
            try {
                output.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                Files.writeString(output, json + "\n")
            } catch (error: IOException) {
                return fail(err, EXIT_FAILED, "could not write $output: $error")
            }
        }
        return EXIT_OK
    }

    /** 標準エラーにメッセージを出して code を返す。 */
    private fun fail(err: PrintStream, code: Int, message: String): Int {
        err.println("fukurou: $message")
        return code
    }

    /** ヘルプを求める引数。 */
    private val HELP = setOf("-h", "--help", "help")

    /** 引数の誤り。 */
    private class UsageError(message: String) : Exception(message)

    /**
     * versions の引数。
     *
     * @property spec バージョン指定
     * @property maxVersions 範囲が解決してよいバージョンの数
     * @property paperChannel 許容する最も不安定なチャンネル
     * @property output JSON を書くファイル（null なら標準出力）
     * @property help ヘルプを求めたか
     */
    private data class VersionsOptions(
        val spec: String,
        val maxVersions: Int,
        val paperChannel: String,
        val output: Path?,
        val help: Boolean,
    ) {
        companion object {
            /** `--name value` と `--name=value` の両方を受け付ける。 */
            fun parse(args: List<String>): VersionsOptions {
                var spec: String? = null
                var maxVersions = VersionSelection.DEFAULT_MAX_VERSIONS
                var paperChannel = VersionSelection.DEFAULT_PAPER_CHANNEL
                var output: Path? = null
                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    index++
                    if (arg in HELP) return VersionsOptions("", maxVersions, paperChannel, output, help = true)
                    // 先頭が "-" でも数字なら位置引数（範囲の下限が無い "-1.21.11" を誤りとして伝えるため）
                    if (!arg.startsWith("--")) {
                        if (spec != null) throw UsageError("unexpected argument '$arg'")
                        spec = arg
                        continue
                    }
                    val name = arg.substringBefore('=')
                    val value = if ('=' in arg) {
                        arg.substringAfter('=')
                    } else {
                        args.getOrNull(index)?.also { index++ } ?: throw UsageError("$name needs a value")
                    }
                    when (name) {
                        "--max-versions" -> maxVersions = value.trim().toIntOrNull()
                            ?: throw UsageError("--max-versions must be an integer, got '$value'")
                        "--paper-channel" -> paperChannel = value.trim()
                        "--output" -> output = Path.of(value)
                        else -> throw UsageError("unknown option '$name'")
                    }
                }
                return VersionsOptions(spec ?: throw UsageError("the version spec is required"), maxVersions, paperChannel, output, help = false)
            }
        }
    }
}
