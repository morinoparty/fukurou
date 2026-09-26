package party.morino.fukurou.engine.session

import party.morino.fukurou.player.Perspective

/**
 * ハーネスが送った F5 の回数から今の視点を数える（runner/player_session.py:21-22,95-107）。
 *
 * F5 を押すたびに一人称 → 三人称（背面）→ 三人称（正面）と巡回するので、回数を 3 で割った余りが視点になる。
 * 画面を開いたまま押した F5 は数え損なうため、確実ではない（ベストエフォート）。
 */
internal class PerspectiveCounter {
    /** 起動（またはリセットでの正規化）以降に押した F5 の回数。 */
    @Volatile
    var presses: Int = 0
        private set

    /** 今の視点。 */
    val current: Perspective get() = Perspective.entries[presses.mod(PERSPECTIVES)]

    /** target にするために押す F5 の回数。 */
    fun pressesTo(target: Perspective): Int = (target.ordinal - presses).mod(PERSPECTIVES)

    /** 一人称へ戻すために押す F5 の回数（(3 - n % 3) % 3）。 */
    fun pressesToFirstPerson(): Int = pressesTo(Perspective.FIRST_PERSON)

    /** F5 を 1 回押した。 */
    fun pressed() {
        presses++
    }

    /** 一人称に戻った（クライアントの起動・正規化の後）。 */
    fun reset() {
        presses = 0
    }

    /** 定数。 */
    private companion object {
        /** 視点の数。3 回で一人称に戻る。 */
        const val PERSPECTIVES = 3
    }
}
