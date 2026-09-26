package party.morino.fukurou

import kotlin.time.Duration

/**
 * 記録される待ち（action "wait"、on null、label "1.5s"）。テストの残り時間で打ち切る。
 *
 * 記録先は StepScope、無ければ実行中のテスト（LeaseRegistry.active()）から探す。どちらも無ければ記録せずに待つ。
 */
public suspend fun pause(duration: Duration) {
    // WP6: StepRunner 経由で wait ステップとして記録する
    TODO("pause($duration) is implemented by the step engine (WP6)")
}
