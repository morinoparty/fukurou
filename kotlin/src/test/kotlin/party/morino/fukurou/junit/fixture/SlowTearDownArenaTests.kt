package party.morino.fukurou.junit.fixture

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.server.GameServer

/** tearDown が打ち切られても、各テストが自分の結果で記録されることを確かめる。ランチャーから実行する。 */
@ExtendWith(SlowTearDownArena::class)
@TestMethodOrder(MethodOrderer.MethodName::class)
class SlowTearDownArenaTests {
    @Test
    fun `a first`(server: GameServer): Unit = runBlocking {
        server.command("say first")
    }

    @Test
    fun `b second`(server: GameServer): Unit = runBlocking {
        server.command("say second")
    }
}
