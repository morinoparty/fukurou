package party.morino.fukurou.engine.paper.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.server.Arena
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location

/** tests/test_isolation.py の golden を固定する。 */
class VanillaResetPlannerTest {
    private val alice = PlayerProfile("Alice", op = true)
    private val bob = PlayerProfile("Bob")

    private fun commands(participants: List<PlayerProfile>, reset: Isolation.Reset = Isolation.Reset()) =
        VanillaResetPlanner.plan(participants, reset).map { it.command }

    @Test
    @DisplayName("Moves players before filling the arena, in the exact Python order")
    fun goldenOrder() {
        val result = commands(listOf(alice, bob))
        assertEquals(
            listOf("gamemode survival Alice", "tp Alice 0.5 -60 -8.5 0 0", "gamemode survival Bob", "tp Bob 2.5 -60 -8.5 0 0"),
            result.subList(0, 4),
        )
        assertEquals(
            listOf(
                "fill -16 -60 -16 15 -37 15 minecraft:air",
                "fill -16 -61 -16 15 -61 15 minecraft:grass_block",
                "fill -16 -63 -16 15 -62 15 minecraft:dirt",
                "fill -16 -64 -16 15 -64 15 minecraft:bedrock",
                "kill @e[type=!player]",
            ),
            result.subList(4, 9),
        )
        assertEquals(listOf("time set noon", "weather clear"), result.subList(9, 11))
        assertEquals(
            listOf(
                "clear Alice",
                "effect clear Alice",
                "effect give Alice minecraft:instant_health 1 10 true",
                "effect give Alice minecraft:saturation 1 10 true",
                "experience set Alice 0 points",
                "experience set Alice 0 levels",
                "title Alice clear",
                "spawnpoint Alice 0.5 -60 -8.5",
                "op Alice",
            ),
            result.subList(11, 20),
        )
        assertEquals("deop Bob", result.last())
        assertEquals(29, result.size)
        // プレイヤー向けのコマンドは対象を持ち、ワールド向けのコマンドは持たない
        val players = VanillaResetPlanner.plan(listOf(alice, bob), Isolation.Reset()).map { it.player }
        assertEquals(listOf("Alice", "Alice", "Bob", "Bob") + List(7) { null } + List(9) { "Alice" } + List(9) { "Bob" }, players)
    }

    @Test
    @DisplayName("Custom spawns and gamemode; spawnpoint takes coordinates only; op ignores Nothing changed")
    fun customSpawn() {
        val reset = Isolation.Reset(gamemode = GameMode.CREATIVE, spawns = mapOf("Bob" to Location(0.5, -60.0, 8.5, 180f, -15f)))
        val calls = VanillaResetPlanner.plan(listOf(bob), reset)
        val result = calls.map { it.command }
        assertEquals(listOf("gamemode creative Bob", "tp Bob 0.5 -60 8.5 180 -15"), result.subList(0, 2))
        assertTrue("spawnpoint Bob 0.5 -60 8.5" in result)
        assertEquals(listOf("Nothing changed"), calls.last().ignore)
        assertEquals(listOf("No entity was found"), calls.single { it.command.startsWith("kill") }.ignore)
    }

    @Test
    @DisplayName("Arena can be disabled or resized")
    fun arena() {
        val off = commands(listOf(alice), Isolation.Reset(arena = null))
        assertTrue(off.none { it.startsWith("fill") || it.startsWith("kill") })
        assertEquals(listOf("time set noon", "weather clear"), off.subList(2, 4))
        val small = commands(listOf(alice), Isolation.Reset(arena = Arena(size = 16, height = 8)))
        assertTrue("fill -8 -60 -8 7 -53 7 minecraft:air" in small)
    }
}
