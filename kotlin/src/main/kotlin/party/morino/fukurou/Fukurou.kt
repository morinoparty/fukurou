package party.morino.fukurou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import party.morino.fukurou.engine.client.PortableMc
import party.morino.fukurou.engine.net.Downloader
import party.morino.fukurou.engine.net.MojangApi
import party.morino.fukurou.engine.plugin.PluginResolver
import party.morino.fukurou.engine.process.ProcessRegistry
import party.morino.fukurou.engine.session.ServerInstance
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.server.ServerDefinition
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM 内で共有するキャッシュ・作業ディレクトリ・出力先・予算を持つルート。生成したサーバーはすべてここが所有する。
 *
 * @property config 設定
 * @param mojangApi Mojang のバージョン情報の取得先（単体テストで手元のサーバーに向ける。null なら本物）
 */
public class Fukurou internal constructor(public val config: FukurouConfig, private val mojangApi: MojangApi?) : AutoCloseable {
    /** 設定から作る。 */
    public constructor(config: FukurouConfig) : this(config, null)

    public companion object {
        /** fukurou.* システムプロパティと FUKUROU_* 環境変数から作る（純粋な FukurouConfig.fromSources を使う）。 */
        public fun fromSystemProperties(): Fukurou {
            // System.getProperties() は Properties（Map<Any, Any>）なので文字列の組だけを取り出す
            val properties = System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) }
            return Fukurou(FukurouConfig.fromSources(properties, System.getenv()))
        }

        /** 共有インスタンス（閉じた後は次の参照で作り直す）。 */
        private var sharedInstance: Fukurou? = null

        /**
         * JUnit 拡張が使う JVM 共有インスタンス。最初の参照で作る。
         *
         * 閉じるのは LeaseRegistry（エンジンの実行の終わりに JUnit がルートストアを閉じるとき）とシャットダウンフック。
         * 同じ JVM で JUnit の実行が 2 回ある（ランチャーを入れ子で使う）場合に備え、閉じた後の参照では作り直す。
         */
        public val shared: Fukurou
            get() = synchronized(this) {
                sharedInstance?.takeUnless { it.closed.get() } ?: fromSystemProperties().also { sharedInstance = it }
            }
    }

    /** 参加前のプレイヤー。操作は持たない。名前の規則は [PlayerProfile] を参照。 */
    public fun player(name: String, op: Boolean = false): PlayerProfile = PlayerProfile(name, op)

    /** 起動前のサーバー定義。configure の後で型の能力と設定の整合を検査する（§1.3）。 */
    public fun server(type: ServerType, configure: ServerSpec.() -> Unit = {}): ServerDefinition {
        // 設定を組み立ててから凍結する。検査は ServerDefinition の生成時に行う
        val spec = ServerSpec(type).apply(configure)
        return ServerDefinition(this, spec, emptyList())
    }

    /** ダウンロード（全サーバーで共有）。 */
    internal val downloader: Downloader by lazy { Downloader() }

    /** Mojang のバージョン情報（マニフェストを 1 回だけ取得する）。 */
    internal val mojang: MojangApi by lazy { mojangApi ?: MojangApi() }

    /** プラグインの解決（URL のダウンロードをこのインスタンスの中で使い回す）。 */
    internal val pluginResolver: PluginResolver by lazy { PluginResolver(config, downloader) }

    /** 生きているサーバー（作成順）。 */
    private val servers = CopyOnWriteArrayList<ServerInstance>()

    /** JVM の終了時に result.json を確定する処理を登録したか。 */
    private val hookRegistered = AtomicBoolean(false)

    /** close 済みか。 */
    private val closed = AtomicBoolean(false)

    /** close 済みか（JUnit の実行の終わりに閉じたかを確かめる）。 */
    internal val isClosed: Boolean get() = closed.get()

    /** portablemc の実行ファイル（無ければダウンロードして展開する）。 */
    internal suspend fun portableMc(): Path = PortableMc.ensure(config.workDir.resolve("tools"), downloader)

    /** サーバーを所有に加える（ServerInstance.open から）。 */
    internal fun register(server: ServerInstance) {
        check(!closed.get()) { "this Fukurou instance is closed" }
        servers += server
        // Gradle の取り消しや SIGTERM でも、走っていたテストを interrupted として書き残す（§5.8）
        if (hookRegistered.compareAndSet(false, true)) {
            ProcessRegistry.addShutdownListener { servers.toList().forEach { it.interrupt() } }
        }
    }

    /** サーバーを所有から外す（ServerInstance.stop から）。 */
    internal fun unregister(server: ServerInstance) {
        servers -= server
    }

    /** 生きているサーバー（クライアント → Xvfb → サーバーの順）をすべて止め、result.json を確定する。冪等。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 後から作ったサーバーから止める（先に作ったサーバーに依存する使い方に備える）
        runBlocking(Dispatchers.IO) {
            servers.toList().asReversed().forEach { server -> runCatching { server.stop() } }
        }
    }
}
