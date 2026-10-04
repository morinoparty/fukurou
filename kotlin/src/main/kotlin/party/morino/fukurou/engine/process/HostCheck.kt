package party.morino.fukurou.engine.process

import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path

/**
 * ホストの確認。
 *
 * サーバーだけを起動するなら Linux で setsid・kill が PATH にあればよい。クライアントを起動するには、さらに x86_64 で
 * Xvfb・xdotool・xmodmap が要る。クライアントの道具は、クライアントをインストール・起動する直前に初めて確かめる
 * （プレイヤーの無いテストクラスは Xvfb の無いホストでも動く）。
 *
 * JVM ごとにそれぞれ 1 回だけ調べ、結果（成功か失敗か）を覚えておく。足りないものはすべて並べ、apt の行を添えて 1 つの SetupException にする。
 * MissingHostPolicy.SKIP の扱い（JUnit の TestAbortedException への変換）は JUnit 側が行う。
 */
internal object HostCheck {
    /** サーバーの起動と停止に要る道具と、それを含む apt のパッケージ。 */
    private val SERVER_TOOLS = linkedMapOf(
        "setsid" to "util-linux",
        "kill" to "procps",
    )

    /** クライアントの起動と入力に要る道具と、それを含む apt のパッケージ。 */
    private val CLIENT_TOOLS = linkedMapOf(
        "Xvfb" to "xvfb",
        "xdotool" to "xdotool",
        "xmodmap" to "x11-xserver-utils",
    )

    /** scripts/install-system-deps.sh と同じ apt の行。 */
    const val APT_LINE: String = "sudo apt-get install --no-install-recommends -y " +
        "xvfb xdotool x11-xserver-utils libgl1 libgl1-mesa-dri libglx-mesa0 libegl1 libopenal1 " +
        "libvulkan1 mesa-vulkan-drivers libxrandr2 libxinerama1 libxcursor1 libxi6"

    /** サーバーだけの 1 回目の結果（null なら問題なし）。 */
    private val serverOutcome: String? by lazy { outcome(clients = false) }

    /** クライアントも含めた 1 回目の結果（null なら問題なし）。 */
    private val clientOutcome: String? by lazy { outcome(clients = true) }

    /**
     * 確認の本体。引数はクライアントの道具まで確かめるか。単体テストは Xvfb の無いホストでも偽の種類を起動できるよう
     * 差し替える（本番では差し替えない）。
     */
    @Volatile
    var probe: (clients: Boolean) -> String? = { clients -> if (clients) clientOutcome else serverOutcome }

    /**
     * 足りないものを示すメッセージ。問題が無ければ null（MissingHostPolicy.SKIP の判定に使う）。
     *
     * @param clients クライアントの道具（Xvfb・xdotool・xmodmap）まで確かめるか
     */
    fun problemMessage(clients: Boolean = true): String? = probe(clients)

    /** サーバーを起動できなければ SetupException。2 回目以降は覚えた結果を返すだけ。 */
    fun ensure(@Suppress("UNUSED_PARAMETER") config: FukurouConfig) {
        problemMessage(clients = false)?.let { throw SetupException(it) }
    }

    /** クライアントを起動できなければ SetupException。クライアントのインストールと起動の直前に呼ぶ。 */
    fun ensureClients() {
        problemMessage(clients = true)?.let { throw SetupException(it) }
    }

    /**
     * 足りないもの（OS・アーキテクチャ・道具）を並べる。純粋関数ではないが、PATH を渡せるのでテストできる。
     *
     * @param clients クライアントの前提（x86_64 と X の道具）も確かめるか
     */
    fun problems(osName: String, osArch: String, path: String, clients: Boolean = true): List<String> = buildList {
        // プロセスグループの制御（setsid と kill）が Linux 前提
        if (!osName.startsWith("Linux")) add("the operating system is $osName, not Linux")
        // PortableMC と Xvfb の前提は x86_64。サーバーだけなら JVM が動けばよい
        if (clients && osArch !in setOf("amd64", "x86_64")) add("the architecture is $osArch, not x86_64")
        val dirs = path.split(':').filter { it.isNotEmpty() }.map { Path.of(it) }
        val tools = if (clients) CLIENT_TOOLS + SERVER_TOOLS else SERVER_TOOLS
        tools.forEach { (tool, pkg) ->
            // 実行はせず、PATH 上に実行できるファイルがあるかだけを見る
            val found = dirs.any { dir -> dir.resolve(tool).let { Files.isRegularFile(it) && Files.isExecutable(it) } }
            if (!found) add("$tool is not on PATH (package $pkg)")
        }
    }

    /** 足りないものの一覧と、入れ方を示すメッセージ。 */
    fun message(problems: List<String>): String =
        "this host cannot run fukurou:\n" + problems.joinToString("\n") { "  - $it" } +
            "\ninstall the system packages with:\n  $APT_LINE" +
            "\n(on GitHub Actions, use morinoparty/fukurou/setup@v3)"

    /** このホストを調べた結果のメッセージ。問題が無ければ null。 */
    private fun outcome(clients: Boolean): String? =
        problems(System.getProperty("os.name"), System.getProperty("os.arch"), System.getenv("PATH").orEmpty(), clients)
            .takeIf { it.isNotEmpty() }
            ?.let(::message)
}
