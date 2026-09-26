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
 * @property serverJava サーバーを起動する java の実行ファイル
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
    val serverJava: Path,
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
         * 優先順位は プロパティ > 環境変数 > `<key>.default` プロパティ > 既定値。値が不正なら [SetupException]。
         */
        public fun fromSources(properties: Map<String, String>, env: Map<String, String>): FukurouConfig {
            // fukurou.* 以外のシステムプロパティ（java.* など）は種類のファクトリに渡さない
            val own = properties.filterKeys { it.startsWith(PREFIX) }
            // 空文字は「未設定」と同じに扱う（Gradle で -Pfukurou.x= と空で渡されることがある）
            fun prop(key: String): String? = own[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun envOf(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }
            // プロパティ > 環境変数 > .default プロパティ の順に最初に見つかった値
            fun layered(key: String, envKey: String?): String? =
                prop(key) ?: envKey?.let(::envOf) ?: prop("$key.default")

            return FukurouConfig(
                acceptEula = parseBoolean("fukurou.acceptEula", layered("fukurou.acceptEula", "FUKUROU_ACCEPT_EULA")) ?: false,
                workDir = Path.of(layered("fukurou.workDir", "FUKUROU_WORK_DIR") ?: ".fukurou-work"),
                outDir = Path.of(layered("fukurou.outDir", "FUKUROU_OUT_DIR") ?: "fukurou-out"),
                // 指定が無ければテストを動かしている JVM と同じ java でサーバーを起動する
                serverJava = Path.of(prop("fukurou.serverJava") ?: currentJava()),
                memoryBudgetMb = prop("fukurou.memoryBudgetMb")?.let { value ->
                    value.toLongOrNull()?.takeIf { it > 0 }
                        ?: throw SetupException("fukurou.memoryBudgetMb must be a positive number of megabytes, got '$value'")
                },
                plugins = own.filterKeys { it.startsWith(PLUGIN_PREFIX) && it.length > PLUGIN_PREFIX.length }
                    .filterValues { it.isNotBlank() }
                    .map { (key, value) -> key.removePrefix(PLUGIN_PREFIX) to Path.of(value.trim()) }
                    .toMap(),
                selectionTests = splitList(prop("fukurou.selection.tests")),
                selectionTags = splitList(prop("fukurou.selection.tags")),
                missingHost = parseMissingHost(prop("fukurou.missingHost")),
                keepWork = parseBoolean("fukurou.keepWork", prop("fukurou.keepWork")) ?: false,
                githubToken = envOf("GITHUB_TOKEN"),
                properties = own,
            )
        }

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

        /** 実行中の JVM の java。取れない環境（一部の JRE）では PATH の java に任せる。 */
        private fun currentJava(): String = ProcessHandle.current().info().command().orElse("java")
    }
}
