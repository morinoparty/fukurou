package party.morino.fukurou.junit

import party.morino.fukurou.player.Player
import party.morino.fukurou.player.PlayerProfile
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * `val alice by player("Alice")` の委譲。宣言（プロパティの初期化）の時点でプレイヤーを拡張に登録し、
 * 参照のたびに今のセッションの参加済みの Player を返す。
 *
 * サーバーを作り直しても（fresh-server・メモリ予算による退避の後）古い Player を持ち続けないよう、値は保持しない。
 *
 * @property profile 宣言したプレイヤー
 */
public class PlayerDelegate internal constructor(private val profile: PlayerProfile) {
    /** 拡張の生成中に呼ばれ、宣言順（= 参加順）にプレイヤーを登録する。 */
    public operator fun provideDelegate(thisRef: GameServerExtension, property: KProperty<*>): ReadOnlyProperty<GameServerExtension, Player> {
        thisRef.declare(profile)
        return ReadOnlyProperty { extension, _ -> extension.server.player(profile.name) }
    }
}
