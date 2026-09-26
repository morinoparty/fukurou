package party.morino.fukurou.engine.step

import java.util.concurrent.ConcurrentHashMap

/**
 * parallel ブロックの検証（scenario/expansion.py:26-32,130-148,206-222 の移植）。
 *
 * レーンの数の上限・入れ子の禁止を起動前に検査し、実行中は「同じプレイヤーへ入力するレーンは 1 本だけ」を守らせる。
 * 同じクライアントへ 2 つのレーンがキーを送ると、打鍵が混ざって結果が再現しないため。
 *
 * @property block このテストでのブロック番号（0 始まり）
 */
internal class LaneGuard(val block: Int) {
    /** プレイヤー名 → そのプレイヤーへ最初に入力したレーン。 */
    private val owners = ConcurrentHashMap<String, Int>()

    /**
     * レーン lane が player へ入力することを登録する。
     *
     * @throws IllegalStateException このブロックの別のレーンが既に player へ入力している
     */
    fun onInput(player: String, lane: Int) {
        // 最初に入力したレーンが持ち主になる。以後は同じレーンからの入力だけを通す
        val owner = owners.putIfAbsent(player, lane) ?: return
        if (owner != lane) throw IllegalStateException("two lanes of parallel block $block sent input to $player")
    }

    /** 検査の定数と、起動前の検査。 */
    companion object {
        /** 1 ブロックのレーンの上限（expansion.py:26 MAX_PARALLEL_LANES）。 */
        const val MAX_LANES: Int = 16

        /**
         * レーンの数を検査する。
         *
         * @throws IllegalArgumentException 上限を超えている
         */
        fun checkLaneCount(count: Int) {
            // レーンごとにクライアントやスレッドを占有するので、上限を超える並列は受け付けない
            require(count <= MAX_LANES) { "a parallel block has $count lanes; at most $MAX_LANES are allowed" }
        }

        /**
         * 入れ子を検査する。
         *
         * @param outerBlock 呼び出し元がいる parallel ブロックの番号（外なら null）
         * @throws IllegalStateException parallel の中で parallel を呼んだ
         */
        fun checkNotNested(outerBlock: Int?) {
            // 入れ子を許すとレーンの番号とステップの並び（lane 0 から）が一意に決まらない
            if (outerBlock != null) throw IllegalStateException("parallel cannot be nested (already inside parallel block $outerBlock)")
        }
    }
}
