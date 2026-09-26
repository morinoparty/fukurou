package party.morino.fukurou.engine.paper.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.fukurou.version.MinecraftVersion

class ComponentCodecTest {
    private val component = Component.text("hi", NamedTextColor.RED)
        .decorate(TextDecoration.BOLD)
        .clickEvent(ClickEvent.runCommand("/help"))

    @Test
    @DisplayName("1.21.4 uses clickEvent and 1.21.6 uses click_event")
    fun clickEventNames() {
        val old = ComponentCodec(MinecraftVersion("1.21.4")).encode(component)
        val new = ComponentCodec(MinecraftVersion("1.21.6")).encode(component)
        assertTrue("\"clickEvent\"" in old, old)
        assertTrue("\"click_event\"" in new, new)
        assertFalse('\n' in new)
        assertTrue("\"color\":\"red\"" in new && "\"bold\":true" in new, new)
    }

    @Test
    @DisplayName("Plain text round-trips")
    fun roundTrip() {
        val codec = ComponentCodec(MinecraftVersion("26.3"))
        val text = Component.text("こんにちは \"world\"")
        assertEquals(text, codec.decode(codec.encode(text)))
    }
}
