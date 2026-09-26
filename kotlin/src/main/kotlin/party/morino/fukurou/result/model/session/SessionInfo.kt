package party.morino.fukurou.result.model.session

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.enums.SessionKind

/**
 * サーバーの起動 1 回分。initial が 1 つと、作り直しごとに fresh-server が 1 つ。
 *
 * @property index 番号（0 始まり）
 * @property kind 種類
 * @property startedAt 開始時刻
 * @property finishedAt 終了時刻
 * @property players このセッションに参加したプレイヤー
 * @property tests このセッションで実行したテストの id（実行順）
 * @property logs このセッションのログ
 * @property failure サーバーがセッションの途中で死んだときのメッセージ
 */
@Serializable
public data class SessionInfo(
    val index: Int,
    val kind: SessionKind,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val players: List<String> = emptyList(),
    val tests: List<String> = emptyList(),
    val logs: List<LogInfo> = emptyList(),
    val failure: String? = null,
)
