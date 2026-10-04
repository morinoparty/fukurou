package party.morino.fukurou.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.server.paper.PaperChannel
import party.morino.fukurou.spi.Capabilities
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.version.MinecraftVersion
import java.lang.reflect.Proxy
import kotlin.reflect.KClass

/** 公開 API の書き方（usage.md の例）がそのまま使えることを確かめる。 */
class ServerApiTest {
    /** 試験用の能力。 */
    private interface Greeting : Capability

    /** 登録しない能力。 */
    private interface Missing : Capability

    /** capability と type だけを答える GameServer。 */
    private fun server(capabilities: Capabilities): GameServer =
        Proxy.newProxyInstance(GameServer::class.java.classLoader, arrayOf(GameServer::class.java)) { _, method, args ->
            when (method.name) {
                "capability" -> {
                    @Suppress("UNCHECKED_CAST")
                    capabilities.get(args[0] as KClass<Capability>)
                }
                "getType" -> Paper("1.21.11")
                else -> throw UnsupportedOperationException(method.name)
            }
        } as GameServer

    @Test
    @DisplayName("Paper takes properties and agent with a String version")
    fun paperStringConstructor() {
        val paper = Paper("1.21.11", agent = false, properties = mapOf("difficulty" to "peaceful"))
        assertEquals(MinecraftVersion("1.21.11"), paper.version)
        assertEquals(PaperChannel.Stable, paper.channel)
        assertFalse(paper.agent)
        assertEquals(mapOf("difficulty" to "peaceful"), paper.properties)
        assertEquals(Paper(MinecraftVersion("1.21.11"), PaperChannel.Alpha, 7), Paper("1.21.11", PaperChannel.Alpha, 7))
    }

    @Test
    @DisplayName("capability<C>() and require<C>() read the capabilities by type")
    fun capabilityByType() {
        val greeting = object : Greeting {}
        val server = server(Capabilities.of(greeting))
        assertSame(greeting, server.capability<Greeting>())
        assertSame(greeting, server.require<Greeting>())
        assertNull(server.capability<Missing>())
        assertThrows<UnsupportedCapabilityException> { server.require<Missing>() }
    }
}
