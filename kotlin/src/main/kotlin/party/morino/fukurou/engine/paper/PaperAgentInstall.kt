package party.morino.fukurou.engine.paper

import party.morino.fukurou.error.SetupException
import party.morino.fukurou.spi.model.AgentEndpoint
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat

/**
 * サーバー内エージェント（FukurouAgent）の導入（v3 設計 §1.1）。
 *
 * fukurou の jar に埋め込んだエージェントの jar を plugins/ に置き、待ち受けのポートとトークンを
 * plugins/FukurouAgent/agent.properties に書く。
 */
internal object PaperAgentInstall {
    /** fukurou の jar の中のエージェントの jar（agent のサブプロジェクトがビルドしたもの）。 */
    const val RESOURCE: String = "/party/morino/fukurou/engine/agent/fukurou-agent.jar"

    /** jar が無いときのメッセージ。 */
    const val MISSING_MESSAGE: String =
        "the fukurou agent jar is missing from the fukurou library; set Paper(agent = false) to run without it"

    /** エージェントが待ち受けるアドレス（ループバックだけ）。 */
    private const val BIND = "127.0.0.1"

    /** トークンのバイト数（16 進で 32 桁）。 */
    private const val TOKEN_BYTES = 16

    /** トークンの乱数。 */
    private val RANDOM = SecureRandom()

    /** クラスパスからエージェントの jar を開く。無ければ null。 */
    fun openResource(): InputStream? = PaperAgentInstall::class.java.getResourceAsStream(RESOURCE)

    /**
     * エージェントの jar を読み切る。
     *
     * @param open jar を開く（テストで差し替える）。null を返せば jar が無い
     * @throws SetupException jar が無い
     */
    fun load(open: () -> InputStream?): ByteArray =
        (open() ?: throw SetupException(MISSING_MESSAGE)).use { it.readAllBytes() }

    /**
     * serverDir にエージェントを入れ、接続先を返す。サーバーディレクトリを作り直した後に呼ぶ。
     *
     * @param serverDir サーバーのディレクトリ
     * @param jar エージェントの jar の中身
     * @param port エージェントが待ち受けるポート
     */
    fun install(serverDir: Path, jar: ByteArray, port: Int): AgentEndpoint {
        val plugins = Files.createDirectories(serverDir.resolve("plugins"))
        Files.write(plugins.resolve("fukurou-agent.jar"), jar)
        val token = newToken()
        val config = Files.createDirectories(plugins.resolve("FukurouAgent"))
        Files.writeString(config.resolve("agent.properties"), "port=$port\ntoken=$token\nbind=$BIND\n")
        return AgentEndpoint(port, token)
    }

    /** セッションごとの使い捨てのトークン（16 バイトの 16 進）。 */
    private fun newToken(): String = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(RANDOM::nextBytes))
}
