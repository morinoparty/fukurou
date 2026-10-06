package party.morino.fukurou

import party.morino.fukurou.error.SetupException
import java.nio.file.Path

/**
 * fukurou の設定。システムプロパティ（Gradle の -Pfukurou.* を -D で渡したもの）と環境変数から作る。
 *
 * 種類（Paper など）に固有の設定は持たない。種類のファクトリは [properties] から自分のキーを読む。
 *
 * @property acceptEula Minecraft の EULA に同意したか。true でなければ何も起動しない
 * @property workDir キャッシュ・ツール・サーバーの作業ディレクトリを置く場所
 * @property outDir result.json とアーティファクトの出力先
 * @property serverJava サーバーを起動する java の選び方（既定は auto）
 * @property memoryBudgetMb 同時に動かすサーバーとクライアントのメモリ予算。null なら最初の取得時に MemAvailable の 9 割
 * @property plugins fukurou.plugin.<key>=<path> の一覧
 * @property selectionTests 選択したテスト（result.json に記録するだけ）
 * @property selectionTags 選択したタグ（result.json に記録するだけ）
 * @property missingHost ホストの道具が足りないときの扱い
 * @property keepWork close の後も servers/<runId>/ を残すか
 * @property githubToken GitHub のリリースを取得するときのトークン。toString では伏せる
 * @property properties fukurou.* のプロパティすべて（種類のファクトリが読む）
 */
public data class FukurouConfig(
    val acceptEula: Boolean,
    val workDir: Path,
    val outDir: Path,
    val serverJava: ServerJava,
    val memoryBudgetMb: Long?,
    val plugins: Map<String, Path>,
    val selectionTests: List<String>,
    val selectionTags: List<String>,
    val missingHost: MissingHostPolicy,
    val keepWork: Boolean,
    val githubToken: String?,
    val properties: Map<String, String>,
) {
    /** トークンをログや例外のメッセージに漏らさないよう、githubToken だけ伏せて表示する。 */
    override fun toString(): String =
        "FukurouConfig(acceptEula=$acceptEula, workDir=$workDir, outDir=$outDir, serverJava=$serverJava, " +
            "memoryBudgetMb=$memoryBudgetMb, plugins=$plugins, selectionTests=$selectionTests, " +
            "selectionTags=$selectionTags, missingHost=$missingHost, keepWork=$keepWork, " +
            "githubToken=${if (githubToken == null) "null" else "***"}, properties=${maskedProperties()})"

    /** properties にトークンが紛れていても表示しないよう、token を含むキーの値を伏せる。 */
    private fun maskedProperties(): Map<String, String> =
        properties.mapValues { (key, value) -> if (key.contains("token", ignoreCase = true)) "***" else value }

    public companion object {
        /** fukurou.* のプロパティの接頭辞。 */
        private const val PREFIX = "fukurou."

        /** プラグインの jar を渡すプロパティの接頭辞（fukurou.plugin.<key>）。 */
        private const val PLUGIN_PREFIX = "fukurou.plugin."

        /**
         * 純粋関数。プロパティと環境変数から設定を作る。単体テストでは props / env を直接渡す。
         *
         * 優先順位は プロパティ > 環境変数（FUKUROU_<UPPER_SNAKE>）> `<key>.default` プロパティ > 既定値。値が不正なら [SetupException]。
         * 環境変数から取った値は [properties] にも入れる（種類のファクトリが同じ値を読めるように）。
         */
        public fun fromSources(properties: Map<String, String>, env: Map<String, String>): FukurouConfig {
            // fukurou.* 以外のシステムプロパティ（java.* など）は種類のファクトリに渡さない
            val own = withEnv(properties.filterKeys { it.startsWith(PREFIX) }, env)
            // 空文字は「未設定」と同じに扱う（Gradle で -Pfukurou.x= と空で渡されることがある）
            fun prop(key: String): String? = own[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun envOf(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }
            // 既知のキーは withEnv で 環境変数と .default を own に重ねてある。既知でないキーのために .default も見る
            fun layered(key: String): String? = prop(key) ?: prop("$key.default")

            return FukurouConfig(
                acceptEula = parseBoolean("fukurou.acceptEula", layered("fukurou.acceptEula")) ?: false,
                // 子プロセスは別の cwd で動くため、argv に入るパスはすべてここで絶対パスにする
                workDir = absolute(layered("fukurou.workDir") ?: ".fukurou-work"),
                outDir = absolute(layered("fukurou.outDir") ?: "fukurou-out"),
                serverJava = parseServerJava(layered("fukurou.serverJava")),
                memoryBudgetMb = layered("fukurou.memoryBudgetMb")?.let { value ->
                    value.toLongOrNull()?.takeIf { it > 0 }
                        ?: throw SetupException("fukurou.memoryBudgetMb must be a positive number of megabytes, got '$value'")
                },
                plugins = own.filterKeys { it.startsWith(PLUGIN_PREFIX) && it.length > PLUGIN_PREFIX.length }
                    .filterValues { it.isNotBlank() }
                    .map { (key, value) -> key.removePrefix(PLUGIN_PREFIX) to absolute(value.trim()) }
                    .toMap(),
                selectionTests = splitList(layered("fukurou.selection.tests")),
                selectionTags = splitList(layered("fukurou.selection.tags")),
                missingHost = parseMissingHost(layered("fukurou.missingHost")),
                keepWork = parseBoolean("fukurou.keepWork", layered("fukurou.keepWork")) ?: false,
                githubToken = envOf("GITHUB_TOKEN"),
                properties = own,
            )
        }

        /**
         * 環境変数からも読む既知のキー（v3 設計 V12）。fukurou.<name> は FUKUROU_<UPPER_SNAKE> に対応する
         * （fukurou.minecraftVersion ↔ FUKUROU_MINECRAFT_VERSION、fukurou.selection.tests ↔ FUKUROU_SELECTION_TESTS）。
         */
        internal val ENV_KEYS: List<String> = listOf(
            "fukurou.acceptEula",
            "fukurou.workDir",
            "fukurou.outDir",
            "fukurou.serverJava",
            "fukurou.memoryBudgetMb",
            "fukurou.missingHost",
            "fukurou.keepWork",
            "fukurou.minecraftVersion",
            "fukurou.paperChannel",
            "fukurou.paperBuild",
            "fukurou.updateBaselines",
            "fukurou.selection.tests",
            "fukurou.selection.tags",
        )

        /** fukurou.<name> に対応する環境変数の名前。キャメルケースの境目と「.」を「_」にして大文字にする。 */
        internal fun envName(key: String): String {
            val name = key.removePrefix(PREFIX)
            val snake = buildString {
                name.forEachIndexed { index, char ->
                    when {
                        char == '.' -> append('_')
                        // 小文字や数字の直後の大文字が単語の境目（memoryBudgetMb → MEMORY_BUDGET_MB）
                        char.isUpperCase() && index > 0 && !name[index - 1].isUpperCase() && name[index - 1] != '.' -> {
                            append('_')
                            append(char)
                        }
                        else -> append(char)
                    }
                }
            }
            return "FUKUROU_" + snake.uppercase(java.util.Locale.ROOT)
        }

        /**
         * 既知のキーのうちプロパティが無い（空を含む）ものに、環境変数の値（無ければ `<key>.default` の値）を入れた写しを返す。
         * Paper.fromProperties などの種類のファクトリも [properties] から同じ値を読めるようにする。
         */
        private fun withEnv(own: Map<String, String>, env: Map<String, String>): Map<String, String> {
            val merged = own.toMutableMap()
            for (key in ENV_KEYS) {
                // プロパティが優先。空のプロパティは未設定と同じなので環境変数で埋める
                if (!own[key].isNullOrBlank()) continue
                val value = env[envName(key)]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: own["$key.default"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: continue
                merged[key] = value
            }
            return merged
        }

        /** JVM の cwd を基準に絶対パスへ正規化する。子プロセスの cwd に左右されないようにする。 */
        private fun absolute(value: String): Path = Path.of(value).toAbsolutePath().normalize()

        /** 区切りを含む（パスで指定された）実行ファイルだけ絶対パスにする。"java" のような名前は PATH の検索に任せる。 */
        private fun executable(value: String): Path = if ('/' in value) absolute(value) else Path.of(value)

        /** "true" / "false"（大文字小文字は問わない）だけを受け付ける。打ち間違いを黙って false にしない。 */
        private fun parseBoolean(key: String, value: String?): Boolean? = when (value?.lowercase()) {
            null -> null
            "true" -> true
            "false" -> false
            else -> throw SetupException("$key must be true or false, got '$value'")
        }

        /** カンマ区切りの一覧。空の要素は捨てる。 */
        private fun splitList(value: String?): List<String> =
            value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        /** fail（既定）か skip。 */
        private fun parseMissingHost(value: String?): MissingHostPolicy = when (value?.lowercase()) {
            null, "fail" -> MissingHostPolicy.FAIL
            "skip" -> MissingHostPolicy.SKIP
            else -> throw SetupException("fukurou.missingHost must be fail or skip, got '$value'")
        }

        /** auto（既定）/ current / java の実行ファイルのパス。 */
        private fun parseServerJava(value: String?): ServerJava = when (value?.lowercase()) {
            null, "auto" -> ServerJava.Auto
            "current" -> ServerJava.Current
            else -> ServerJava.Path(executable(value!!))
        }
    }
}
