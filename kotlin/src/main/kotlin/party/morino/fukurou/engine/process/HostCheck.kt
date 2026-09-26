package party.morino.fukurou.engine.process

import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path

/**
 * ホストの確認。Linux の x86_64 で、Xvfb・xdotool・xmodmap・setsid・kill が PATH にあること。
 *
 * JVM ごとに 1 回だけ調べ、結果（成功か失敗か）を覚えておく。足りないものはすべて並べ、apt の行を添えて 1 つの SetupException にする。
 * MissingHostPolicy.SKIP の扱い（JUnit の TestAbortedException への変換）は JUnit 側が行う。
 */
internal object HostCheck {
    /** 必要な道具と、それを含む apt のパッケージ。 */
    private val TOOLS = linkedMapOf(
        "Xvfb" to "xvfb",
        "xdotool" to "xdotool",
        "xmodmap" to "x11-xserver-utils",
        "setsid" to "util-linux",
        "kill" to "procps",
    )

    /** scripts/install-system-deps.sh と同じ apt の行。 */
    const val APT_LINE: String = "sudo apt-get install --no-install-recommends -y " +
        "xvfb xdotool x11-xserver-utils libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 libopenal1 " +
        "libvulkan1 mesa-vulkan-drivers libxrandr2 libxinerama1 libxcursor1 libxi6"

    /** 1 回目の結果（null なら問題なし）。 */
    private val outcome: String? by lazy {
        problems(System.getProperty("os.name"), System.getProperty("os.arch"), System.getenv("PATH").orEmpty())
            .takeIf { it.isNotEmpty() }
            ?.let(::message)
    }

    /**
     * 確認の本体。単体テストは Xvfb の無いホストでも偽の種類を起動できるよう差し替える（本番では差し替えない）。
     */
    @Volatile
    var probe: () -> String? = { outcome }

    /** 足りないものを示すメッセージ。問題が無ければ null（MissingHostPolicy.SKIP の判定に使う）。 */
    fun problemMessage(): String? = probe()

    /** ホストが条件を満たさなければ SetupException。2 回目以降は覚えた結果を返すだけ。 */
    fun ensure(@Suppress("UNUSED_PARAMETER") config: FukurouConfig) {
        problemMessage()?.let { throw SetupException(it) }
    }

    /** 足りないもの（OS・アーキテクチャ・道具）を並べる。純粋関数ではないが、PATH を渡せるのでテストできる。 */
    fun problems(osName: String, osArch: String, path: String): List<String> = buildList {
        // PortableMC と Xvfb の前提は Linux の x86_64
        if (!osName.startsWith("Linux")) add("the operating system is $osName, not Linux")
        if (osArch !in setOf("amd64", "x86_64")) add("the architecture is $osArch, not x86_64")
        val dirs = path.split(':').filter { it.isNotEmpty() }.map { Path.of(it) }
        TOOLS.forEach { (tool, pkg) ->
            // 実行はせず、PATH 上に実行できるファイルがあるかだけを見る
            val found = dirs.any { dir -> dir.resolve(tool).let { Files.isRegularFile(it) && Files.isExecutable(it) } }
            if (!found) add("$tool is not on PATH (package $pkg)")
        }
    }

    /** 足りないものの一覧と、入れ方を示すメッセージ。 */
    fun message(problems: List<String>): String =
        "this host cannot run fukurou:\n" + problems.joinToString("\n") { "  - $it" } +
            "\ninstall the system packages with:\n  $APT_LINE" +
            "\n(on GitHub Actions, use morinoparty/fukurou/setup@v2)"
}
