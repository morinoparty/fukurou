package party.morino.fukurou

import party.morino.fukurou.engine.step.ParallelRunner

/**
 * レーンを宣言し、ブロックを抜けたときに同時に走らせ、全レーンの終了を待つ。
 *
 * 1 つのレーンが失敗しても他のレーンは最後まで走る。サーバーが落ちたときとテストの期限を過ぎたときだけ他を止める（§4.12）。
 * レーンは 16 本まで、入れ子は不可、同じプレイヤーへのクライアント入力は 1 レーンから。
 */
public suspend fun parallel(block: ParallelScope.() -> Unit) {
    // 宣言だけを先に集め、ブロックを抜けてから同時に走らせる
    ParallelRunner().apply(block).run()
}
