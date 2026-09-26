package party.morino.fukurou.engine.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path

class ClientLaunchCommandTest {
    private val command = ClientLaunchCommand(
        Path.of("/w/tools/portablemc-5.0.4/portablemc"), Path.of("/w/cache/minecraft"), "26.3",
        Path.of("/w/servers/r/clients/Alice"), "1536M", "Alice",
    )

    private val base = listOf(
        "/w/tools/portablemc-5.0.4/portablemc", "--output", "machine", "--main-dir", "/w/cache/minecraft",
        "start", "26.3", "--mc-dir", "/w/servers/r/clients/Alice", "--jvm-arg=-Xms512M,-Xmx1536M",
        "--resolution", "1280x720", "--username", "Alice",
    )

    @Test
    @DisplayName("Install appends --dry and launch appends the join server")
    fun installAndLaunch() {
        assertEquals(base + "--dry", command.install())
        assertEquals(
            base + listOf("--join-server", "127.0.0.1", "--join-server-port", "25570"),
            command.launch(InetSocketAddress("127.0.0.1", 25570)),
        )
    }

    @Test
    @DisplayName("The options text is CLIENT_OPTIONS verbatim")
    fun options() {
        assertEquals(12, ClientOptions.TEXT.lines().filter { it.isNotEmpty() }.size)
        assertEquals(true, ClientOptions.TEXT.contains("lang:en_us\n") && ClientOptions.TEXT.endsWith("soundCategory_master:0.0\n"))
    }
}
