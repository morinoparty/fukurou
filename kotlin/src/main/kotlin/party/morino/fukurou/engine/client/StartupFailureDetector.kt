package party.morino.fukurou.engine.client

/**
 * クライアントのログから「もう起動できない」状態を見分ける（runner/client_startup.py）。
 *
 * クライアントは描画バックエンドを作れなくてもプロセスが終了せず、そのまま止まることがある
 * （例: 26.3 の renderpearl で OpenGL も Vulkan も作れなかった場合）。プロセスの生死だけを見ていると
 * 参加待ちのタイムアウトまで何分も待ってしまうので、ログの内容で早めに打ち切る。
 */
internal object StartupFailureDetector {
    /** 1 行で致命的と分かるもの。 */
    private val FATAL_LINE = Regex("""^.*(?:/FATAL\]|Game crashed!|Minecraft has crashed).*$""", RegexOption.MULTILINE)

    /** 描画バックエンドの作成失敗（例: "Failed to create backend OpenGL"）。 */
    private val BACKEND_FAILURE = Regex("""^.*Failed to create backend (\w+).*$""", RegexOption.MULTILINE)

    /** 描画バックエンドの作成成功（例: "Using graphics backend OpenGL" / "Created backend Vulkan"）。 */
    private val BACKEND_SUCCESS = Regex("""Using graphics backend|Created (?:graphics )?backend""", RegexOption.IGNORE_CASE)

    /** 26.3 の renderpearl が試すバックエンド。すべて失敗したら描画できない。 */
    private val KNOWN_BACKENDS = setOf("OpenGL", "Vulkan")

    /** 起動を続けられないと分かる行があれば、その行（複数あればまとめたもの）を返す。無ければ null。 */
    fun detect(text: String): String? {
        FATAL_LINE.find(text)?.let { return it.value.trim() }
        val failures = BACKEND_FAILURE.findAll(text).toList()
        val failed = failures.map { it.groupValues[1] }.toSet()
        // どれか 1 つでも作れていれば、残りのバックエンドの失敗は問題にならない
        if (failures.isNotEmpty() && BACKEND_SUCCESS.find(text) == null && failed.containsAll(KNOWN_BACKENDS)) {
            return "no graphics backend could be created: " + failures.joinToString(" / ") { it.value.trim() }
        }
        return null
    }
}
