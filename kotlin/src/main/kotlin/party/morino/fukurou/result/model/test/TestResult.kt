package party.morino.fukurou.result.model.test

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.enums.TestStatus
import party.morino.fukurou.result.model.kind.IsolationMode
import party.morino.fukurou.result.model.step.StepResult

/**
 * テスト 1 件の結果。
 *
 * @property id tests/<id>/ のパスとビューアのルートに使う id
 * @property name 表示名
 * @property order 実行順
 * @property source "junit:<fqcn>#<method>"
 * @property sha256 テストクラスの sha256
 * @property tags タグ
 * @property isolation 隔離方法
 * @property timeout 期限（秒）
 * @property versions @MinecraftVersions の指定
 * @property session 実行したセッションの index。走らなかったテストは null
 * @property status 結果
 * @property skipReason skipped のときの理由
 * @property players 参加プレイヤー
 * @property reset リセットの記録
 * @property steps ステップ
 * @property failure 失敗
 * @property screenshots スクリーンショット
 * @property logRanges ログのパス → このテストの間の行
 * @property startedAt 開始時刻（秒精度）
 * @property durationMs 所要時間
 */
@Serializable
public data class TestResult(
    val id: String,
    val name: String,
    val order: Int,
    val source: String,
    val sha256: String,
    val tags: List<String> = emptyList(),
    val isolation: IsolationMode = IsolationMode.RESET,
    val timeout: Double = 600.0,
    val versions: String? = null,
    val session: Int? = null,
    val status: TestStatus,
    val skipReason: String? = null,
    val players: List<TestPlayer> = emptyList(),
    val reset: ResetInfo? = null,
    val steps: List<StepResult> = emptyList(),
    val failure: TestFailure? = null,
    val screenshots: List<ScreenshotInfo> = emptyList(),
    val logRanges: Map<String, LogRange>? = null,
    val startedAt: String? = null,
    val durationMs: Long? = null,
) {
    init {
        // 飛ばした理由が無いと、ビューアで「なぜ走らなかったか」を示せない（model.py の検証と同じ）
        require(status != TestStatus.SKIPPED || !skipReason.isNullOrEmpty()) {
            "skipReason is required when status is 'skipped'"
        }
    }
}
