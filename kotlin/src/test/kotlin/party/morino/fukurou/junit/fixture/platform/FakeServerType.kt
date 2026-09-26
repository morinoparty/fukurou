package party.morino.fukurou.junit.fixture.platform

import party.morino.fukurou.server.ServerType
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.version.MinecraftVersion

/** Minecraft の代わりにシェルを起動する種類（拡張の一連の流れを Xvfb なしで確かめる）。 */
object FakeServerType : ServerType {
    override val id: String = "fake"
    override val minecraftVersion: MinecraftVersion = MinecraftVersion("1.21.4")

    override fun createPlatform(services: PlatformServices): ServerPlatform = FakePlatform(this, services)
}
