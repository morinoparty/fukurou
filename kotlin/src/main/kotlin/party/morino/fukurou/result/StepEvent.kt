package party.morino.fukurou.result

import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.step.ParallelInfo
import party.morino.fukurou.result.model.step.StepPhase
import java.time.Instant

/**
 * エンジンが記録係へ渡すステップの出来事。stepStarted では結果の項目が null、stepFinished で埋まる。
 *
 * 最終的な steps の添字は記録係が決める（parallel ブロックは lane 0 から並べ直す）ので、
 * エンジンは provisionalId でステップを識別する。
 *
 * @property provisionalId テスト内で一意な仮の id（failure.stepIndex や screenshots[].stepIndex の付け替えに使う）
 * @property phase どの層から来たか
 * @property fixture phase が fixture のときの名前
 * @property on "server" / プレイヤー名 / null
 * @property action アクション名（Python と同じ文字列）
 * @property label 一覧表示用の短い説明
 * @property startedAt 開始時刻
 * @property parallel parallel ブロックの中ならその位置
 * @property status 結果（stepFinished のみ）
 * @property finishedAt 終了時刻（stepFinished のみ）
 * @property durationMs 所要時間（stepFinished のみ。放棄したレーンのステップは null）
 * @property error 失敗の理由
 * @property screenshot screenshot アクションの保存先の相対パス
 */
internal data class StepEvent(
    val provisionalId: Long,
    val phase: StepPhase,
    val fixture: String?,
    val on: String?,
    val action: String,
    val label: String,
    val startedAt: Instant,
    val parallel: ParallelInfo? = null,
    val status: StepStatus? = null,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val screenshot: String? = null,
)
