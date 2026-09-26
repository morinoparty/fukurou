package party.morino.fukurou.spi

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.spi.capability.CommandEcho
import party.morino.fukurou.spi.capability.PlayerCommands
import party.morino.fukurou.spi.capability.WorldCommands
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.BlockPos

class CapabilitiesTest {
    /** 1 クラスで 2 つの能力を持つ実装（PaperPlayerCommands と同じ形）。 */
    private class EchoAndWorld : CommandEcho, WorldCommands {
        override fun issuedCommand(player: String, commandWithoutSlash: String): Regex = Regex(player)

        override fun fill(from: BlockPos, to: BlockPos, block: String, world: Key): List<CommandCall> = emptyList()

        override fun setBlock(at: BlockPos, block: String, world: Key): List<CommandCall> = emptyList()

        override fun time(ticks: Long): List<CommandCall> = emptyList()

        override fun weatherClear(): List<CommandCall> = emptyList()
    }

    @Test
    @DisplayName("Every capability interface of an implementation is registered")
    fun registersEveryInterface() {
        val implementation = EchoAndWorld()
        val capabilities = Capabilities.of(implementation)
        assertSame(implementation, capabilities.get(CommandEcho::class))
        assertSame(implementation, capabilities.get(WorldCommands::class))
        assertNull(capabilities.get(PlayerCommands::class))
    }

    @Test
    @DisplayName("The same capability provided twice is rejected")
    fun rejectsDuplicates() {
        assertThrows<IllegalArgumentException> { Capabilities.of(EchoAndWorld(), EchoAndWorld()) }
    }
}
