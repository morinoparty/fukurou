package party.morino.fukurou.junit.fixture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.server.GameServer

/** 2 台のサーバーを登録したクラス。GameServer の引数はどちらか決まらないので解決できない。 */
@ExtendWith(FakeArena::class, TwinArena::class)
class TwinArenaTests {
    @Test
    fun `resolves each extension by its own type`(fake: FakeArena, twin: TwinArena) {
        check(fake.server !== twin.server)
    }

    @Test
    fun `cannot pick a GameServer`(@Suppress("UNUSED_PARAMETER") server: GameServer) {}
}
