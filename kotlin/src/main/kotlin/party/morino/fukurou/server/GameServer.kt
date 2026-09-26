package party.morino.fukurou.server

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.audience.ForwardingAudience
import net.kyori.adventure.key.Key
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import party.morino.fukurou.log.LogView
import party.morino.fukurou.player.Player
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.Worlds
import java.net.InetSocketAddress
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 起動済みのサーバー。サーバー全体（参加中の全プレイヤー）への Audience でもある。
 *
 * Audience の操作は参加中の各プレイヤーへ配られる。プレイヤーがいなければ何もしない（ForwardingAudience の意味）。
 */
public interface GameServer : ForwardingAudience, AutoCloseable {
    /** サーバーの種類。 */
    public val type: ServerType

    /** result id の末尾。 */
    public val label: String

    /** "<type.id>-<version>-<label>"（例: paper-26.3-stamp-arena）。 */
    public val resultId: String

    /** クライアントの接続先（127.0.0.1:<port>）。 */
    public val joinAddress: InetSocketAddress

    /** 参加済みのプレイヤー（参加順）。 */
    public val players: List<Player>

    /** サーバーのコンソールの記録。テストごとの窓で見る。 */
    public val log: LogView

    /** プロキシ（Velocity）の後ろのサーバー。Paper は常に空（§10）。 */
    public val backends: List<GameServer> get() = emptyList()

    /** プレイヤーを参加させる。クライアントは JVM 全体で 1 台ずつ起動する（session.py:99-100）。 */
    public suspend fun join(profile: PlayerProfile): Player

    /** 宣言順に 1 人ずつ参加させる（並列にはしない）。 */
    public suspend fun join(vararg profiles: PlayerProfile): List<Player>

    /** 参加済みのプレイヤー。いなければ参加者の一覧つきで IllegalArgumentException。 */
    public fun player(name: String): Player

    /** 種類のコマンド経路へ送る（先頭の "/" は外す）。FailOnError で応答がエラーなら CommandFailedError。 */
    public suspend fun command(command: String, check: CommandCheck = CommandCheck.FailOnError): CommandResponse

    /** WorldCommands の糖衣。block はブロック状態の文字列（"minecraft:stone"、"minecraft:oak_stairs[facing=east]"）。 */
    public suspend fun fill(from: BlockPos, to: BlockPos, block: String, world: Key = Worlds.OVERWORLD)

    /** 1 ブロックを置く。 */
    public suspend fun setBlock(at: BlockPos, block: String, world: Key = Worlds.OVERWORLD)

    /** 時刻を設定する。 */
    public suspend fun time(ticks: Long)

    /** 天気を晴れにする。 */
    public suspend fun weatherClear()

    /** 種類が提供する能力。無ければ null。 */
    public fun <C : Capability> capability(kind: KClass<C>): C?

    /** サーバーログの現在の末尾（= log.mark()）。 */
    public fun mark(): LogMark

    /** サーバーログに pattern が出るまで待つ。 */
    public suspend fun awaitLog(pattern: Regex, timeout: Duration = 60.seconds, after: LogMark? = null): LogMatch

    /** サーバーログに pattern の行があれば LogAssertionError。 */
    public fun assertNoLog(pattern: Regex, after: LogMark? = null)

    /** ステップを phase=fixture / fixture=name で記録する。名前は ^[A-Za-z0-9][A-Za-z0-9_.-]*$。 */
    public suspend fun <T> fixture(name: String, block: suspend GameServer.() -> T): T

    /** JUnit を使わないときの記録スコープ。mark → relaunch → reset → block → finish → result.json 書き換え。 */
    public suspend fun <T> test(
        id: String,
        name: String = id,
        tags: Set<String> = emptySet(),
        block: suspend GameServer.() -> T,
    ): T

    /** クライアント → Xvfb → サーバーの順に止め、ログを回収する。 */
    public suspend fun stop()

    /** runBlocking(Dispatchers.IO) { stop() } と同じ。 */
    override fun close()

    /** 参加中のプレイヤー全員。 */
    override fun audiences(): Iterable<Audience> = players
}

/** 能力が無いときに UnsupportedCapabilityException を投げる版。 */
public inline fun <reified C : Capability> GameServer.require(): C =
    capability(C::class) ?: throw UnsupportedCapabilityException(type.id, C::class.simpleName ?: C::class.java.name)
