package party.morino.fukurou

/**
 * レーンを宣言し、ブロックを抜けたときに同時に走らせ、全レーンの終了を待つ。
 *
 * 1 つのレーンが失敗しても他のレーンは最後まで走る。サーバーが落ちたときとテストの期限を過ぎたときだけ他を止める（§4.12）。
 */
public suspend fun parallel(block: ParallelScope.() -> Unit) {
    // WP6: ParallelRunner と LaneGuard で実行する
    TODO("parallel is implemented by the step engine (WP6): $block")
}
