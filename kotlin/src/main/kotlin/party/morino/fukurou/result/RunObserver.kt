package party.morino.fukurou.result

import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.event.TestOutcome
import party.morino.fukurou.result.model.enums.RunFailurePhase
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.session.LogInfo
import party.morino.fukurou.result.model.suite.PluginInfo
import party.morino.fukurou.result.model.test.LogRange
import party.morino.fukurou.result.model.test.ResetInfo
import party.morino.fukurou.result.model.test.ScreenshotInfo
import party.morino.fukurou.spi.model.PlatformInfo

/**
 * エンジン → 記録係の出来事（§6.1）。v1 の実装は RunRecorder だけ。
 *
 * 1 テストの順序は testStarted → resetFinished → stepStarted / stepFinished … → blockFinished → testFinished。
 */
internal interface RunObserver {
    /** 起動より前（ダウンロードより前）に、計画したテストを not run として書く。 */
    fun runPlanned(tests: List<PlannedTest>)

    /** セッション（サーバーの起動）の開始。 */
    fun sessionStarted(index: Int, kind: SessionKind)

    /** サーバーの準備完了。minecraft / java / plugins を埋める。 */
    fun serverReady(info: PlatformInfo, plugins: List<PluginInfo>)

    /** プレイヤーの参加。 */
    fun playerJoined(name: String)

    /** テストの開始。 */
    fun testStarted(testId: String, session: Int, players: List<PlayerProfile>)

    /** リセットの終了。 */
    fun resetFinished(testId: String, reset: ResetInfo)

    /** ステップの開始。 */
    fun stepStarted(testId: String, event: StepEvent)

    /** ステップの終了。 */
    fun stepFinished(testId: String, event: StepEvent)

    /** parallel ブロックの終了（BlockRecorder がレーン 0 から順に書き出す）。 */
    fun blockFinished(testId: String, block: Int)

    /** スクリーンショットの撮影。 */
    fun screenshotTaken(testId: String, info: ScreenshotInfo, provisionalStepId: Long?)

    /** テストの終了。 */
    fun testFinished(testId: String, outcome: TestOutcome, logRanges: Map<String, LogRange>)

    /** テストを実行しなかった。 */
    fun testSkipped(testId: String, reason: String)

    /** run 全体の失敗。 */
    fun runFailed(phase: RunFailurePhase, message: String)

    /** セッションの終了。 */
    fun sessionFinished(index: Int, logs: List<LogInfo>, failure: String?)

    /** run の終了。 */
    fun runFinished()
}
