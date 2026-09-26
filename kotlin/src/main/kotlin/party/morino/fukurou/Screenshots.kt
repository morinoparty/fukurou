package party.morino.fukurou

import party.morino.fukurou.player.Player
import party.morino.fukurou.player.Screenshot

/** 1 プレイヤー 1 レーンで同時に撮る。戻り値は引数の順。 */
public suspend fun screenshot(vararg players: Player, name: String): List<Screenshot> {
    // WP6: parallel { players.forEach { lane { it.screenshot(name) } } } と同じ
    TODO("screenshot(${players.size} players, $name) is implemented by the step engine (WP6)")
}

/** 1 プレイヤー 1 レーンで同時に撮る。戻り値はこの Iterable の順。 */
public suspend fun Iterable<Player>.screenshot(name: String): List<Screenshot> = screenshot(*toList().toTypedArray(), name = name)
