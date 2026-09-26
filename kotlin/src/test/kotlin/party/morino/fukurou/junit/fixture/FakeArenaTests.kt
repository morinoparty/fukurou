package party.morino.fukurou.junit.fixture

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.junit.annotation.MinecraftVersions
import party.morino.fukurou.server.GameServer

/** ランチャーから実行する（Gradle の test は build.gradle.kts でこのパッケージを除く）。 */
@ExtendWith(FakeArena::class)
class FakeArenaTests {
    @Test
    fun `sends a command`(arena: FakeArena, server: GameServer): Unit = runBlocking {
        assertSame(arena.server, server)
        server.command("say hi")
    }

    @Test
    fun `fails on an error response`(server: GameServer): Unit = runBlocking {
        server.command("fail now")
    }

    @Test
    @MinecraftVersions("99.0-")
    fun `runs only on a future version`() {}
}
