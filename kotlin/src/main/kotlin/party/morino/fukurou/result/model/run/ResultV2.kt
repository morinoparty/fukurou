package party.morino.fukurou.result.model.run

import kotlinx.serialization.Serializable
import party.morino.fukurou.result.model.enums.RunStatus
import party.morino.fukurou.result.model.session.LogInfo
import party.morino.fukurou.result.model.session.SessionInfo
import party.morino.fukurou.result.model.suite.CiInfo
import party.morino.fukurou.result.model.suite.PlayerInfo
import party.morino.fukurou.result.model.suite.PluginInfo
import party.morino.fukurou.result.model.suite.SelectionInfo
import party.morino.fukurou.result.model.suite.SuiteInfo
import party.morino.fukurou.result.model.test.TestResult

/**
 * result.json のルート（schema/result.v2.json、schemaVersion 2）。
 *
 * @property schemaVersion 常に 2
 * @property id "<type>-<version>-<label>"。artifact の中の run ディレクトリ名と一致させる
 * @property label サーバーの label（新しい任意項目）
 * @property status run の結果
 * @property fukurou 結果を書いた fukurou
 * @property minecraft テストしたサーバー
 * @property java 使った Java
 * @property plugins 入れたプラグイン
 * @property suite スイートの設定（JUnit 拡張の設定）
 * @property selection 選択したテスト
 * @property players 参加させるプレイヤー
 * @property summary ステータスごとの件数
 * @property sessions サーバーの起動ごとの記録
 * @property tests テストの結果
 * @property failure run を止めた失敗
 * @property logs セッションに属さないログ（harness.log）
 * @property startedAt 開始時刻（秒精度の UTC）
 * @property finishedAt 終了時刻
 * @property durationMs 所要時間
 * @property ci GitHub Actions の情報
 */
@Serializable
public data class ResultV2(
    val schemaVersion: Int = 2,
    val id: String,
    val label: String? = null,
    val status: RunStatus,
    val fukurou: FukurouInfo,
    val minecraft: MinecraftInfo,
    val java: JavaInfo = JavaInfo(),
    val plugins: List<PluginInfo> = emptyList(),
    val suite: SuiteInfo? = null,
    val selection: SelectionInfo = SelectionInfo(),
    val players: List<PlayerInfo> = emptyList(),
    val summary: Summary = Summary(),
    val sessions: List<SessionInfo> = emptyList(),
    val tests: List<TestResult> = emptyList(),
    val failure: RunFailure? = null,
    val logs: List<LogInfo> = emptyList(),
    val startedAt: String,
    val finishedAt: String? = null,
    val durationMs: Long? = null,
    val ci: CiInfo? = null,
) {
    init {
        // 契約の schemaVersion は const 2。別の値を書くとビューアが読めない
        require(schemaVersion == 2) { "schemaVersion must be 2, got $schemaVersion" }
    }
}
