package party.morino.fukurou

import party.morino.fukurou.player.Player
import party.morino.fukurou.player.Screenshot

/** 1 プレイヤー 1 レーンで同時に撮る。戻り値は引数の順。 */
public suspend fun screenshot(vararg players: Player, name: String): List<Screenshot> {
    // レーンは同時に終わるので、引数の位置に結果を入れて順序を保つ
    val results = arrayOfNulls<Screenshot>(players.size)
    parallel {
        players.forEachIndexed { index, player -> lane { results[index] = player.screenshot(name) } }
    }
    // parallel が例外なく戻ったなら全レーンが撮り終えている
    return results.map { requireNotNull(it) { "a screenshot lane finished without a result" } }
}

/** 1 プレイヤー 1 レーンで同時に撮る。戻り値はこの Iterable の順。 */
public suspend fun Iterable<Player>.screenshot(name: String): List<Screenshot> = screenshot(*toList().toTypedArray(), name = name)
