package party.morino.fukurou.junit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.junit.fixture.FakeArena
import party.morino.fukurou.junit.fixture.TwinArena

class GameServerParameterRuleTest {
    @Test
    @DisplayName("resolves GameServer when exactly one fukurou server is registered")
    fun sole() {
        assertNull(GameServerExtension.gameServerProblem(listOf(FakeArena::class.java), "StampTest", inConstructor = false))
    }

    @Test
    @DisplayName("names every extension when two servers are registered")
    fun two() {
        val message = GameServerExtension.gameServerProblem(listOf(FakeArena::class.java, TwinArena::class.java), "StampTest", inConstructor = false)
        assertEquals("2 fukurou servers are registered on StampTest (FakeArena, TwinArena); declare the parameter as FakeArena or TwinArena", message)
    }

    @Test
    @DisplayName("points constructor injection to a method parameter")
    fun constructor() {
        val message = GameServerExtension.gameServerProblem(listOf(FakeArena::class.java), "StampTest", inConstructor = true).orEmpty()
        assertTrue(message.endsWith("use a method parameter"), message)
    }
}
