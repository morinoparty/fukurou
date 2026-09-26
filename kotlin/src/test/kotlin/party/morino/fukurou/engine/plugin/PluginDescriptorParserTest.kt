package party.morino.fukurou.engine.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PluginDescriptorParserTest {
    @Test
    @DisplayName("Reads top-level scalars as strings")
    fun readsScalars() {
        val text = "name: Example\nversion: 1.10\nmain: dev.example.Example\nprefix: PFX # comment\n"
        assertEquals(mapOf("name" to "Example", "version" to "1.10", "prefix" to "PFX"), PluginDescriptorParser.parse(text))
    }

    @Test
    @DisplayName("Strips quotes and ignores nested keys")
    fun quotesAndNesting() {
        val text = "﻿name: \"Quoted\"\r\nversion: '2'\r\ncommands:\n  name: nested\n"
        assertEquals(mapOf("name" to "Quoted", "version" to "2"), PluginDescriptorParser.parse(text))
    }

    @Test
    @DisplayName("Rejects an unclosed flow value")
    fun rejectsBroken() {
        assertThrows<IllegalArgumentException> { PluginDescriptorParser.parse("name: [unclosed\nmain: a.B\n") }
    }
}
