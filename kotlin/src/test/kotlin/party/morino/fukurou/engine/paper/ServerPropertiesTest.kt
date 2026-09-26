package party.morino.fukurou.engine.paper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ServerPropertiesTest {
    private val managed = mapOf(
        "server-port" to "25601",
        "enable-rcon" to "true",
        "rcon.port" to "25602",
        "rcon.password" to "secret",
        "max-players" to "2",
    )

    @Test
    @DisplayName("Defaults are kept verbatim, including the escaped flat generator")
    fun defaults() {
        val text = ServerProperties.render(ServerProperties.DEFAULT_PROPERTIES)
        assertTrue("white-list=false\n" in text)
        assertTrue("level-type=minecraft\\:flat\n" in text)
        assertTrue(
            "generator-settings={\"layers\"\\:[{\"block\"\\:\"minecraft\\:bedrock\",\"height\"\\:1}," +
                "{\"block\"\\:\"minecraft\\:dirt\",\"height\"\\:2},{\"block\"\\:\"minecraft\\:grass_block\",\"height\"\\:1}]," +
                "\"biome\"\\:\"minecraft\\:plains\"}\n" in text,
        )
    }

    @Test
    @DisplayName("Parse skips comments and keeps raw values")
    fun parse() {
        val text = "# comment\n! also comment\n\nmotd = Hello=World\nlevel-type=minecraft\\:normal\n"
        assertEquals(mapOf("motd" to "Hello=World", "level-type" to "minecraft\\:normal"), ServerProperties.parse(text))
        assertThrows<IllegalArgumentException> { ServerProperties.parse("no-separator") }
    }

    @Test
    @DisplayName("Layers apply in order, managed keys win and ignored keys are reported")
    fun merge() {
        val merged = ServerProperties.merge(
            listOf(
                mapOf("difficulty" to "easy", "motd" to "from files"),
                mapOf("motd" to "from input", "server-port" to "1", "rcon.password" to "x"),
            ),
            managed,
        )
        assertEquals("easy", merged.properties["difficulty"])
        assertEquals("from input", merged.properties["motd"])
        assertEquals("25601", merged.properties["server-port"])
        assertEquals("secret", merged.properties["rcon.password"])
        assertEquals(listOf("server-port", "rcon.password"), merged.ignored)
    }
}
