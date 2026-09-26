package party.morino.fukurou.result.event

import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.model.kind.IsolationMode

/**
 * 実行を計画したテスト。記録係は起動前にこれで not run の stub（skipped）を書く。
 *
 * @property className テストクラスの FQCN
 * @property method テストメソッドの名前
 * @property id result の tests[].id
 * @property name 表示名
 * @property tags タグ
 * @property order 計画順
 * @property source "junit:<fqcn>#<method>"
 * @property sha256 テストクラスの sha256
 * @property isolation 隔離方法
 * @property timeoutSeconds 期限（秒）
 * @property versions @MinecraftVersions の指定
 * @property players 参加プレイヤー
 */
internal data class PlannedTest(
    val className: String,
    val method: String,
    val id: String,
    val name: String,
    val tags: List<String>,
    val order: Int,
    val source: String,
    val sha256: String,
    val isolation: IsolationMode = IsolationMode.RESET,
    val timeoutSeconds: Double = 600.0,
    val versions: String? = null,
    val players: List<PlayerProfile> = emptyList(),
)
