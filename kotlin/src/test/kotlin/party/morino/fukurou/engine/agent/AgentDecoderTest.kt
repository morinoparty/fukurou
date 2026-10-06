package party.morino.fukurou.engine.agent

import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AgentDecoderTest {
    /** どの版のサーバーの書き方でも同じ Component になる。 */
    private val expected: Component = Component.text("a", NamedTextColor.RED)
        .clickEvent(ClickEvent.runCommand("/x"))
        .hoverEvent(HoverEvent.showText(Component.text("h")))

    @Test
    @DisplayName("Reads components written by Paper before 1.21.5 (camelCase events)")
    fun legacyComponent() {
        val json = """{"text":"a","color":"red","clickEvent":{"action":"run_command","value":"/x"},"hoverEvent":{"action":"show_text","contents":{"text":"h"}}}"""
        assertEquals(expected, AgentDecoder.component(Json.parseToJsonElement(json)))
    }

    @Test
    @DisplayName("Reads components written by Paper 1.21.5 and later (snake_case events)")
    fun modernComponent() {
        val json = """{"text":"a","color":"red","click_event":{"action":"run_command","command":"/x"},"hover_event":{"action":"show_text","value":{"text":"h"}}}"""
        assertEquals(expected, AgentDecoder.component(Json.parseToJsonElement(json)))
    }

    @Test
    @DisplayName("Reads a plain string component")
    fun stringComponent() {
        assertEquals(Component.text("hi"), AgentDecoder.component(Json.parseToJsonElement("\"hi\"")))
    }
}
