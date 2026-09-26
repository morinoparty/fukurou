package party.morino.fukurou.engine.paper.command

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.error.ServerUnavailableException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** プロセス内の偽の RCON サーバーで確かめる。 */
class RconChannelTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val received = CopyOnWriteArrayList<RconCodec.Packet>()

    @AfterEach
    fun close() = server.close()

    /** 1 接続を受け、ログインに authId で答え、コマンドには reply で答える。 */
    private fun serveOnce(password: String, reply: String) = thread(isDaemon = true) {
        server.accept().use { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val login = RconCodec.decode(input).also(received::add)
            val authId = if (login.payload == password) login.requestId else RconCodec.AUTH_FAILED_ID
            output.write(RconCodec.encode(authId, 2, ""))
            if (authId == RconCodec.AUTH_FAILED_ID) return@use
            val command = RconCodec.decode(input).also(received::add)
            output.write(RconCodec.encode(command.requestId, 0, reply))
        }
    }

    @Test
    @DisplayName("Logs in, sends the command and returns the reply")
    fun command() = runBlocking {
        serveOnce("secret", "There are 0 of a max of 2 players online")
        val response = RconChannel("127.0.0.1", server.localPort, "secret").send("list")
        assertEquals("There are 0 of a max of 2 players online", response.text)
        assertEquals(listOf(3 to "secret", 2 to "list"), received.map { it.type to it.payload })
    }

    @Test
    @DisplayName("Authentication id -1 is ServerUnavailable")
    fun authFailed() {
        serveOnce("secret", "")
        val error = assertThrows<ServerUnavailableException> {
            runBlocking { RconChannel("127.0.0.1", server.localPort, "wrong").send("list") }
        }
        assertEquals("RCON authentication failed", error.message)
    }

    @Test
    @DisplayName("A closed port is ServerUnavailable and an oversized command fails before connecting")
    fun unavailable() {
        val port = server.localPort
        server.close()
        assertThrows<ServerUnavailableException> { runBlocking { RconChannel("127.0.0.1", port, "x").send("list") } }
        assertThrows<IllegalArgumentException> { runBlocking { RconChannel("127.0.0.1", port, "x").send("a".repeat(2000)) } }
    }
}
