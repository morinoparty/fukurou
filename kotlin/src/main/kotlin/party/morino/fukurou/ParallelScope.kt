package party.morino.fukurou

/** parallel { } の中でレーンを宣言するスコープ。レーンはブロックを抜けたときに同時に走る（§4.12）。 */
@FukurouDsl
public interface ParallelScope {
    /** レーンを 1 つ宣言する。宣言順がレーン番号（0 始まり）になり、result.json では lane 0 から順に並ぶ。 */
    public fun lane(body: suspend () -> Unit)
}
