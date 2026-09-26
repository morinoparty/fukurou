package party.morino.fukurou.junit.fixture

import party.morino.fukurou.Fukurou
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.junit.fixture.platform.FakeEnvironment
import party.morino.fukurou.junit.fixture.platform.FakeServerType
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType

/** FakeArena と並べて登録する 2 台目の偽のサーバー。 */
class TwinArena : GameServerExtension() {
    override fun fukurou(): Fukurou = FakeEnvironment.fukurou

    override fun type(config: FukurouConfig): ServerType = FakeServerType

    override fun ServerSpec.configure() {
        isolation = Isolation.None
    }
}
