package party.morino.fukurou

import party.morino.fukurou.engine.step.StepRunner
import kotlin.time.Duration

/**
 * 記録される待ち（action "wait"、on null、label "1.5s"）。テストの残り時間で打ち切る。
 *
 * 記録先は StepScope、無ければ実行中のテストから探す。どちらも無ければ記録せずに待つ。
 * 期限を過ぎると [party.morino.fukurou.error.HarnessTimeoutException]。
 */
public suspend fun pause(duration: Duration) {
    // 記録と期限の打ち切りは StepRunner が行う
    StepRunner.pause(duration)
}
