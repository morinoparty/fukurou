package party.morino.fukurou.junit

/**
 * サーバーとクライアントのメモリの見積もりと、予算に収めるための退避の順序（§5.2）。純粋な計算だけを持つ。
 *
 * 見積もりはヒープに JVM 自身（メタスペース・スレッド・ネイティブ）の分を足したもの。退避するのは使われていない
 * （どのテストクラスも参照していない）サーバーだけで、最後に使った時刻の古い順に止める。
 *
 * @property budgetMb 予算（MB）
 */
internal class MemoryBudget(val budgetMb: Long) {
    /**
     * 使われていないサーバーのうち、止める必要があるもの（古い順）を返す。
     *
     * @param label 起動しようとしているサーバーの名前（メッセージ用）
     * @param neededMb 起動しようとしているサーバーの見積もり
     * @param inUse 使用中で止められないサーバー（名前 → 見積もり）
     * @param idle 使われていないが動いているサーバー（最後に使った時刻の古い順）
     * @param sizeOf idle の要素の見積もり
     * @param nameOf idle の要素の名前（メッセージ用）
     * @return 止めるサーバー（止める順）
     * @throws ServerBudgetException 使用中のサーバーだけで予算を超える（何を止めても足りない）
     */
    fun <T> evictions(
        label: String,
        neededMb: Long,
        inUse: Map<String, Long>,
        idle: List<T>,
        sizeOf: (T) -> Long,
        nameOf: (T) -> String,
    ): List<T> {
        // 止められないものだけで超えるなら、起動する前に内訳を示して止める
        val required = neededMb + inUse.values.sum()
        if (required > budgetMb) throw ServerBudgetException(overBudgetMessage(label, neededMb, inUse))
        // 動いたままでも収まる分だけ残し、足りない分を古い順に止める
        var total = required + idle.sumOf(sizeOf)
        val evicted = mutableListOf<T>()
        for (candidate in idle) {
            if (total <= budgetMb) break
            total -= sizeOf(candidate)
            evicted += candidate
        }
        return evicted
    }

    /** 予算を超えたときのメッセージ。各サーバーの見積もりと、予算の上げ方を示す。 */
    fun overBudgetMessage(label: String, neededMb: Long, inUse: Map<String, Long>): String {
        val lines = listOf("$label: $neededMb MB") + inUse.map { (name, mb) -> "$name (in use by this test class): $mb MB" }
        return "the fukurou servers needed together exceed the memory budget of $budgetMb MB " +
            "(${neededMb + inUse.values.sum()} MB needed):\n" + lines.joinToString("\n") { "  - $it" } +
            "\nraise the budget with -Pfukurou.memoryBudgetMb=<MB> (on a larger runner), " +
            "or declare fewer players or smaller heaps"
    }

    /** 見積もりと予算の求め方。 */
    companion object {
        /** サーバーの JVM のヒープ以外の分（MB）。 */
        const val SERVER_OVERHEAD_MB: Long = 750

        /** クライアントの JVM のヒープ以外の分（MB、LWJGL とソフトウェア描画のネイティブを含む）。 */
        const val CLIENT_OVERHEAD_MB: Long = 900

        /** 予算に使う MemAvailable の割合（残りは OS と Gradle の分）。 */
        const val AVAILABLE_SHARE: Double = 0.9

        /** -Xmx の値（"2G"、"1536M"、"1048576k"、接尾辞なしはバイト）。 */
        private val HEAP = Regex("^(\\d+)([kKmMgGtT]?)$")

        /** /proc/meminfo の MemAvailable の行。 */
        private val MEM_AVAILABLE = Regex("^MemAvailable:\\s+(\\d+)\\s*kB", RegexOption.MULTILINE)

        /**
         * サーバー 1 台と、その全プレイヤーのクライアントの見積もり（MB）。
         *
         * @param serverHeap サーバーの -Xmx
         * @param clientHeap クライアントの -Xmx
         * @param players 参加するプレイヤーの数
         */
        fun estimateMb(serverHeap: String, clientHeap: String, players: Int): Long =
            heapMb(serverHeap) + SERVER_OVERHEAD_MB + players * (heapMb(clientHeap) + CLIENT_OVERHEAD_MB)

        /**
         * -Xmx の値を MB にする（切り上げ）。
         *
         * @throws IllegalArgumentException 形式が違う
         */
        fun heapMb(value: String): Long {
            val match = requireNotNull(HEAP.matchEntire(value.trim())) { "heap size '$value' must look like 2G or 1536M" }
            val amount = match.groupValues[1].toLong()
            // Java の -Xmx と同じく接尾辞なしはバイト
            val bytes = when (match.groupValues[2].lowercase()) {
                "" -> amount
                "k" -> amount * 1024
                "m" -> amount * 1024 * 1024
                "g" -> amount * 1024 * 1024 * 1024
                else -> amount * 1024 * 1024 * 1024 * 1024
            }
            return (bytes + MB - 1) / MB
        }

        /**
         * /proc/meminfo の内容から既定の予算（MemAvailable の 9 割、MB）を求める。MemAvailable が無ければ null。
         */
        fun fromMemInfo(text: String): Long? {
            val availableKb = MEM_AVAILABLE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            return (availableKb / 1024 * AVAILABLE_SHARE).toLong()
        }

        /** 1 MB のバイト数。 */
        private const val MB: Long = 1024 * 1024
    }
}
