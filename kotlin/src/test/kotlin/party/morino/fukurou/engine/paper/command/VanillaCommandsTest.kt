package party.morino.fukurou.engine.paper.command

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.Location
import party.morino.fukurou.world.Worlds

class VanillaCommandsTest {
    @Test
    @DisplayName("Teleport runs in the location's world")
    fun teleport() {
        val call = VanillaCommands.teleport("Alice", Location(0.5, -49.0, 0.5, 0f, 30f, Worlds.NETHER))
        assertEquals("execute in minecraft:the_nether run tp Alice 0.5 -49 0.5 0 30", call.command)
        assertEquals("Alice", call.player)
    }

    @Test
    @DisplayName("Give uses the item key and fill/setblock run in the world")
    fun giveAndBlocks() {
        assertEquals("give Alice minecraft:diamond 3", VanillaCommands.give("Alice", Key.key("diamond"), 3).command)
        assertEquals(
            "execute in minecraft:overworld run fill 0 -60 0 2 -58 2 minecraft:stone",
            VanillaCommands.fill(BlockPos(0, -60, 0), BlockPos(2, -58, 2), "minecraft:stone", Worlds.OVERWORLD).command,
        )
        assertEquals(
            "execute in minecraft:the_end run setblock 1 2 3 minecraft:oak_stairs[facing=east]",
            VanillaCommands.setBlock(BlockPos(1, 2, 3), "minecraft:oak_stairs[facing=east]", Worlds.END).command,
        )
    }

    @Test
    @DisplayName("Op and deop ignore 'Nothing changed'; the echo pattern escapes the command")
    fun opAndEcho() {
        assertEquals(listOf("Nothing changed"), VanillaCommands.op("Alice").ignore)
        assertEquals("deop Alice", VanillaCommands.deop("Alice").command)
        assertEquals(listOf("Nothing changed"), VanillaCommands.deop("Alice").ignore)
        val echo = VanillaCommands.issuedCommand("Alice", "st :thinking-face:")
        assertTrue(echo.containsMatchIn("[12:00:01 INFO]: Alice issued server command: /st :thinking-face:"))
        assertTrue(VanillaCommands.joined("Al").containsMatchIn("Al joined the game"))
        assertTrue(!VanillaCommands.joined("Al").containsMatchIn("Hal joined the game"))
    }
}
