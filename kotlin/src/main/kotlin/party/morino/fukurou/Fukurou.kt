package party.morino.fukurou

import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.server.ServerDefinition
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType

/**
 * JVM 内で共有するキャッシュ・作業ディレクトリ・出力先・予算を持つルート。生成したサーバーはすべてここが所有する。
 *
 * @property config 設定
 */
public class Fukurou(public val config: FukurouConfig) : AutoCloseable {
    public companion object {
        /** fukurou.* システムプロパティと FUKUROU_* 環境変数から作る（純粋な FukurouConfig.fromSources を使う）。 */
        public fun fromSystemProperties(): Fukurou {
            // System.getProperties() は Properties（Map<Any, Any>）なので文字列の組だけを取り出す
            val properties = System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) }
            return Fukurou(FukurouConfig.fromSources(properties, System.getenv()))
        }

        /**
         * JUnit 拡張が使う JVM 共有インスタンス。最初の参照で作る。
         *
         * 閉じるのは LeaseRegistry の close とシャットダウンフック（WP6 / WP7）。
         */
        public val shared: Fukurou by lazy { fromSystemProperties() }
    }

    /** 参加前のプレイヤー。操作は持たない。名前の規則は [PlayerProfile] を参照。 */
    public fun player(name: String, op: Boolean = false): PlayerProfile = PlayerProfile(name, op)

    /** 起動前のサーバー定義。configure の後で型の能力と設定の整合を検査する（§1.3）。 */
    public fun server(type: ServerType, configure: ServerSpec.() -> Unit = {}): ServerDefinition {
        // 設定を組み立ててから凍結する。検査は ServerDefinition の生成時に行う
        val spec = ServerSpec(type).apply(configure)
        return ServerDefinition(this, spec, emptyList())
    }

    /** 生きているサーバー（クライアント → Xvfb → サーバーの順）をすべて止め、result.json を確定する。冪等。 */
    override fun close() {
        // WP6: 所有するサーバーを止め、記録を確定する
        TODO("Fukurou.close is implemented by the session engine (WP6)")
    }
}
