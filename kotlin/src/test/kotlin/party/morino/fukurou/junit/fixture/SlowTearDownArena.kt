package party.morino.fukurou.junit.fixture

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import party.morino.fukurou.Fukurou
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.junit.fixture.platform.FakeEnvironment
import party.morino.fukurou.junit.fixture.platform.FakeServerType
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** tearDown が利用者の withTimeout で打ち切られる偽のサーバー。 */
class SlowTearDownArena : GameServerExtension() {
    override fun fukurou(): Fukurou = FakeEnvironment.fukurou

    override fun type(config: FukurouConfig): ServerType = FakeServerType

    override fun ServerSpec.configure() {
        isolation = Isolation.None
    }

    override suspend fun GameServer.tearDown() {
        withTimeout(10.milliseconds) { delay(1.seconds) }
    }
}
