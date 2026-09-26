package party.morino.fukurou.engine.paper

import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.spi.Capabilities
import party.morino.fukurou.spi.CommandChannel
import party.morino.fukurou.spi.PlatformServices
import party.morino.fukurou.spi.PlatformSession
import party.morino.fukurou.spi.ResponseCheck
import party.morino.fukurou.spi.ServerPlatform
import party.morino.fukurou.spi.model.PlatformInfo
import party.morino.fukurou.spi.model.ProvisionRequest
import party.morino.fukurou.spi.model.Provisioned

/**
 * Paper の ServerPlatform（§4.4）。
 *
 * @property type Paper の種類の値
 * @property services エンジンのサービス
 */
internal class PaperPlatform(
    override val type: Paper,
    private val services: PlatformServices,
) : ServerPlatform {
    override suspend fun provision(request: ProvisionRequest): Provisioned = TODO("WP3: PaperPlatform.provision($request, $services)")

    override val readyPattern: Regex get() = TODO("WP3: PaperPlatform.readyPattern")

    override suspend fun confirmReady(channel: CommandChannel?): Boolean = TODO("WP3: PaperPlatform.confirmReady($channel)")

    override val portConflictPattern: Regex? get() = TODO("WP3: PaperPlatform.portConflictPattern")

    override fun joinedPattern(player: String): Regex = TODO("WP3: PaperPlatform.joinedPattern($player)")

    override fun openChannel(provisioned: Provisioned): CommandChannel? = TODO("WP3: PaperPlatform.openChannel($provisioned)")

    override val responseCheck: ResponseCheck get() = TODO("WP3: PaperPlatform.responseCheck")

    override fun bind(session: PlatformSession): Capabilities = TODO("WP3: PaperPlatform.bind($session)")

    override suspend fun requestStop(channel: CommandChannel?): Unit = TODO("WP3: PaperPlatform.requestStop($channel)")

    override fun describe(provisioned: Provisioned): PlatformInfo = TODO("WP3: PaperPlatform.describe($provisioned)")
}
