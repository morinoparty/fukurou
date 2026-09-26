package party.morino.fukurou.server

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import party.morino.fukurou.Fukurou
import party.morino.fukurou.engine.session.ServerInstance
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.plugin.PluginSource
import party.morino.fukurou.result.model.enums.SessionKind
import java.nio.file.Path
import kotlin.time.Duration

/**
 * 凍結した ServerSpec。作成時に label の形式を検査し、違反は SetupException。
 *
 * 種類の能力と設定の整合（plugins があるのに PluginSupport が無い、Reset なのに ResetPlanner が無い）は
 * ServerPlatform.bind で能力が分かる start() の中で、ダウンロードより前に検査する。
 */
public class ServerDefinition internal constructor(
    internal val fukurou: Fukurou,
    spec: ServerSpec,
    /** 宣言したプレイヤー（宣言順 = 参加順）。JUnit 拡張が使う。 */
    internal val players: List<PlayerProfile>,
) {
    /** サーバーの種類。 */
    public val type: ServerType = spec.type

    /** result id の末尾と出力ディレクトリ名。 */
    public val label: String = spec.label

    /** 隔離方法。 */
    internal val isolation: Isolation = spec.isolation

    /** 起動を待つ時間。 */
    internal val startTimeout: Duration = spec.startTimeout

    /** クライアントのインストールを待つ時間。 */
    internal val installTimeout: Duration = spec.installTimeout

    /** 参加を待つ時間。 */
    internal val joinTimeout: Duration = spec.joinTimeout

    /** テスト 1 件の期限。 */
    internal val testTimeout: Duration = spec.testTimeout

    /** サーバーの -Xmx。 */
    internal val serverHeap: String = spec.serverHeap

    /** クライアントの -Xmx。 */
    internal val clientHeap: String = spec.clientHeap

    /** 宣言したプラグイン（role to source、宣言順）。 */
    internal val plugins: List<Pair<String, PluginSource>> = spec.pluginSet.declared.toList()

    /** 最初にコピーするディレクトリ。 */
    internal val serverFiles: List<Path> = spec.serverFileDirectories.toList()

    init {
        // label は run id とディレクトリ名に使うので、build_manifest の規則に合わない値は起動前に弾く
        if (!LABEL.matches(label) || label.length > MAX_LABEL_LENGTH) {
            throw SetupException(
                "server label '$label' must be kebab case (${LABEL.pattern}) and at most $MAX_LABEL_LENGTH characters",
            )
        }
    }

    /**
     * 起動し、準備完了（種類のレディネス + プラグイン有効化確認）まで待つ。宣言済みのプレイヤーがいれば全員を参加させる。
     *
     * 失敗したら run の失敗を result.json に書き、起動したプロセスを止めてから投げる。
     */
    public suspend fun start(): GameServer {
        // ダウンロードより前に run ディレクトリと result.json（計画だけ）を用意する
        val server = ServerInstance.open(fukurou, this, suite = null, planned = emptyList())
        try {
            server.start(SessionKind.INITIAL)
            server.install(players)
            players.forEach { server.join(it) }
        } catch (error: Throwable) {
            // 記録は start / join が済ませている。途中まで起動したものを止めて result.json を確定する
            withContext(NonCancellable) { server.stop() }
            throw error
        }
        return server
    }

    internal companion object {
        /** label の形（kebab case）。 */
        internal val LABEL = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

        /** label の最大長。 */
        internal const val MAX_LABEL_LENGTH = 40
    }
}
