package party.morino.fukurou.result.model.step

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.enums.StepStatus

/**
 * ステップ 1 つの結果。
 *
 * @property index そのテストの steps の添字
 * @property phase どの層から来たか
 * @property fixture phase が fixture のときだけ fixture の名前
 * @property on "server" / プレイヤー名 / null（共通アクション）
 * @property action アクション名（Python と同じ文字列）
 * @property label 一覧表示用の短い説明
 * @property status 結果
 * @property durationMs 所要時間
 * @property error 失敗の理由
 * @property screenshot screenshot アクションのときだけ保存先の相対パス
 * @property parallel parallel ブロックの中なら、そのブロックとレーン
 * @property repeat repeat ブロックの中なら外側から順に何回目か（空の一覧は不可）
 * @property startedAt 開始時刻（ミリ秒精度）
 * @property finishedAt 終了時刻（ミリ秒精度）
 */
@Serializable
public data class StepResult(
    val index: Int,
    val phase: StepPhase = StepPhase.TEST,
    val fixture: String? = null,
    val on: String? = null,
    val action: String,
    val label: String,
    val status: StepStatus,
    val durationMs: Long? = null,
    val error: String? = null,
    val screenshot: String? = null,
    val parallel: ParallelInfo? = null,
    val repeat: List<RepeatInfo>? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
) {
    init {
        // fixture 名は phase と対応していないと、ビューアの折りたたみの見出しが食い違う
        require((phase == StepPhase.FIXTURE) == (fixture != null)) {
            "fixture must be set exactly when phase is 'fixture'"
        }
        // スキーマの minItems: 1。空の一覧は null で表す
        require(repeat == null || repeat.isNotEmpty()) { "repeat must be null or non-empty" }
    }
}
