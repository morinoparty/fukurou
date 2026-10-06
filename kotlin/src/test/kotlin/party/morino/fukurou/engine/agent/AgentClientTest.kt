package party.morino.fukurou.engine.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.time.Duration.Companion.seconds

class AgentClientTest {
    @Test
    @DisplayName("Reads newline-delimited UTF-8 lines and drops a trailing CR")
    fun readLines() {
        val input = ByteArrayInputStream("{\"a\":\"ふくろう\"}\r\n\n{}\n".toByteArray())
        assertEquals("{\"a\":\"ふくろう\"}", AgentClient.readLine(input, 64))
        assertEquals("", AgentClient.readLine(input, 64))
        assertEquals("{}", AgentClient.readLine(input, 64))
        assertNull(AgentClient.readLine(input, 64))
    }

    @Test
    @DisplayName("Refuses a line over the cap and a line cut by EOF")
    fun lineLimits() {
        assertThrows<AgentProtocolException> { AgentClient.readLine(ByteArrayInputStream("x".repeat(65).toByteArray()), 64) }
        assertThrows<AgentConnectionLostException> { AgentClient.readLine(ByteArrayInputStream("{\"id\"".toByteArray()), 64) }
    }

    @Test
    @DisplayName("Rejects a hello answer with another protocol version")
    fun protocolMismatch() {
        FakeAgent().use { agent ->
            // hello の代わりに版の違う応答を返す偽物
            val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
            Thread.ofVirtual().start {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write("{\"id\":0,\"ok\":true,\"result\":{\"protocol\":2}}\n".toByteArray())
                    socket.getOutputStream().flush()
                    Thread.sleep(200)
                }
            }
            assertThrows<AgentProtocolException> { runBlocking { AgentClient.open(server.localPort, agent.token, 2.seconds) } }
            server.close()
        }
    }

    @Test
    @DisplayName("Pending requests fail with AgentConnectionLostException when the agent goes away")
    fun pendingFailsOnClose() {
        FakeAgent { }.use { agent ->
            val client = runBlocking { AgentClient.open(agent.port, agent.token, 2.seconds) }
            val pending = client.send("ping")
            agent.close()
            val error = assertThrows<AgentConnectionLostException> { runBlocking { pending.response.await() } }
            assertFalse(client.isOpen, error.message)
            assertThrows<AgentConnectionLostException> { client.send("ping") }
            client.close()
            assertEquals("3.0.0", client.hello["agentVersion"].toString().trim('"'))
        }
    }
}
