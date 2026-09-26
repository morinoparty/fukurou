package party.morino.fukurou.result.model.suite

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.kind.IsolationMode

/**
 * 実行に使ったスイート（JUnit 拡張）の設定。
 *
 * @property source "junit:<拡張の FQCN>"
 * @property sha256 拡張のクラスファイルの sha256
 * @property isolation テストが指定しないときの隔離方法
 * @property settle リセット後に待つ秒数
 * @property gamemode リセット時のゲームモード
 * @property arena アリーナの大きさ。Disabled はリセットを無効にした
 */
@Serializable
public data class SuiteInfo(
    val source: String? = null,
    val sha256: String? = null,
    val isolation: IsolationMode = IsolationMode.RESET,
    val settle: Double = 2.0,
    val gamemode: String = "survival",
    val arena: ArenaInfo? = null,
)
