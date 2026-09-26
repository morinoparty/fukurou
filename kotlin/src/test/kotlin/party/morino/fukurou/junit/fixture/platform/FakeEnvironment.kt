package party.morino.fukurou.junit.fixture.platform

import party.morino.fukurou.Fukurou
import java.util.concurrent.CopyOnWriteArrayList

/** 偽のサーバーを使う拡張が共有する状態（ランチャーで走らせるテストが設定する）。 */
object FakeEnvironment {
    /** 拡張が使う Fukurou（一時ディレクトリの出力先と手元の Mojang）。 */
    @Volatile
    lateinit var fukurou: Fukurou

    /** 偽のコマンド経路が受け取ったコマンド（送った順）。 */
    val commands: MutableList<String> = CopyOnWriteArrayList()
}
