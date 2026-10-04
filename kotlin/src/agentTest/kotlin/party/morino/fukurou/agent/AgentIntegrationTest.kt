package party.morino.fukurou.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.FukurouConfig
import party.morino.fukurou.error.AgentRequestException
import party.morino.fukurou.error.ServerTaskException
import party.morino.fukurou.event.ServerEvent
import party.morino.fukurou.junit.GameServerExtension
import party.morino.fukurou.plugin.PluginSource
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.server.ServerSpec
import party.morino.fukurou.server.ServerType
import party.morino.fukurou.server.execute
import party.morino.fukurou.server.paper.Paper
import party.morino.fukurou.state.EntityQuery
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.Worlds
import kotlin.time.Duration.Companion.seconds

/**
 * プレイヤーの無い Paper にエージェントを入れて、状態の取得・イベント・execute を本物のサーバーで確かめる（v3 設計 §1・§2.1）。
 *
 * クライアントを起動しないので Xvfb などは要らない。コンパニオンプラグインは -Pfukurou.plugin.companion=<jar>
 * （agentTest タスクが渡す）で、e2e:echo / e2e:count-blocks / e2e:fail / e2e:async のタスクを登録する。
 */
class AgentArena : GameServerExtension() {
    /** -Pfukurou.minecraftVersion が無ければ 1.21.4。 */
    override fun type(config: FukurouConfig): ServerType = Paper.fromProperties(config, defaultVersion = "1.21.4")

    override fun ServerSpec.configure() {
        label = "agent"
        // テストの間で何も戻さない（ブロックやエンティティは各テストが自分の場所を使う）
        isolation = Isolation.None
        plugins { underTest(PluginSource.systemProperty("companion")) }
    }

    /** プレイヤーがいないとチャンクが読み込まれない（新しい版はスポーンのチャンクも保たない）ので、テストが使う範囲を常に読み込む。 */
    override suspend fun GameServer.onStarted() {
        command("forceload add 0 0 31 31")
    }
}

/** e2e:echo の引数と戻り値（そのまま返る）。 */
@Serializable
data class EchoArgs(val message: String, val count: Int, val tags: List<String>)

/** e2e:count-blocks の引数（直方体の中の type のブロックの数を返す）。 */
@Serializable
data class CountBlocksArgs(val world: String, val x1: Int, val y1: Int, val z1: Int, val x2: Int, val y2: Int, val z2: Int, val type: String)

@ExtendWith(AgentArena::class)
class AgentIntegrationTest {
    /** RCON のコマンドは RemoteServerCommandEvent（ServerCommandEvent とは別の HandlerList）で届くので両方を見る。 */
    private val commandEvents = arrayOf("ServerCommandEvent", "RemoteServerCommandEvent")

    /** block が T を投げることを確かめ、その例外を返す（suspend のまま呼ぶ）。 */
    private suspend inline fun <reified T : Throwable> thrown(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("expected ${T::class.java.name} to be thrown")
    }

    /** command のプロパティに text を含むイベント。 */
    private fun commandContains(text: String): (ServerEvent) -> Boolean = { it.string("command")?.contains(text) == true }

    @Test
    @DisplayName("currentTick advances across awaitTicks(5)")
    suspend fun ticks(server: GameServer) {
        val before = server.currentTick()
        server.awaitTicks(5)
        val after = server.currentTick()
        assertTrue(after >= before + 5, "tick went from $before to $after")
    }

    @Test
    @DisplayName("block() returns the type and block data that setBlock placed")
    suspend fun block(server: GameServer) {
        val at = BlockPos(8, 100, 8)
        server.setBlock(at, "minecraft:oak_stairs[facing=east]")
        val block = server.block(at)
        assertEquals(Key.key("minecraft:oak_stairs"), block.type)
        assertEquals(Worlds.OVERWORLD, block.world)
        assertTrue("facing=east" in block.data, block.data)
        assertEquals(listOf(8, 100, 8), listOf(block.x, block.y, block.z))
    }

    @Test
    @DisplayName("worldState() describes the overworld")
    suspend fun world(server: GameServer) {
        val world = server.worldState()
        assertEquals(Worlds.OVERWORLD, world.key)
        assertTrue(world.time in 0..23999, "time ${world.time}")
        assertEquals(emptyList<String>(), world.players)
    }

    @Test
    @DisplayName("entities() finds a summoned armor stand")
    suspend fun entities(server: GameServer) {
        server.command("summon minecraft:armor_stand 16 100 16 {NoGravity:1b,Tags:[\"fukurou-agent\"]}")
        val stands = server.entities(EntityQuery(type = Key.key("minecraft:armor_stand")))
        assertTrue(stands.isNotEmpty(), "no armor stand found")
        val tagged = server.entities(EntityQuery(type = Key.key("minecraft:armor_stand"), tag = "fukurou-agent"))
        assertTrue(tagged.all { "fukurou-agent" in it.tags } && tagged.isNotEmpty(), tagged.toString())
    }

    @Test
    @DisplayName("records the command event of an RCON command and finds it by its command field")
    suspend fun recordCommand(server: GameServer) {
        server.events.record(*commandEvents).use { recorder ->
            server.command("say hi")
            val event = recorder.await(30.seconds, commandContains("say hi"))
            assertTrue(event.simpleName.endsWith("ServerCommandEvent"), event.type)
            assertTrue(recorder.events().isNotEmpty())
        }
    }

    @Test
    @DisplayName("expect subscribes before the action runs")
    suspend fun expect(server: GameServer) {
        val event = server.events.expect("RemoteServerCommandEvent", 30.seconds, commandContains("say expect")) {
            server.command("say expect")
        }
        assertEquals("say expect", event.string("command"))
    }

    @Test
    @DisplayName("execute round-trips a serializable value through e2e:echo")
    suspend fun echo(server: GameServer) {
        val args = EchoArgs("hello", 3, listOf("a", "b"))
        val back: EchoArgs = server.execute("e2e:echo", args)
        assertEquals(args, back)
    }

    @Test
    @DisplayName("execute<Int> counts the blocks placed by fill")
    suspend fun countBlocks(server: GameServer) {
        server.fill(BlockPos(24, 100, 24), BlockPos(25, 100, 25), "minecraft:stone")
        val count = server.execute<CountBlocksArgs, Int>("e2e:count-blocks", CountBlocksArgs("minecraft:overworld", 24, 100, 24, 25, 100, 25, "minecraft:stone"))
        assertEquals(4, count)
    }

    @Test
    @DisplayName("a task that throws is ServerTaskException with the remote type")
    suspend fun fail(server: GameServer) {
        val error = thrown<ServerTaskException> { server.execute("e2e:fail") }
        assertEquals("e2e:fail", error.task)
        assertEquals("java.lang.IllegalStateException", error.remoteType)
        assertTrue(error.remoteStackTrace.orEmpty().isNotEmpty())
    }

    @Test
    @DisplayName("a task that returns a CompletionStage is awaited")
    suspend fun async(server: GameServer) {
        // e2e:async は次の tick で、その時点の tick を返す
        val before = server.currentTick()
        val tick = server.execute<Long>("e2e:async")
        assertTrue(tick >= before, "async task completed at tick $tick, before $before")
    }

    @Test
    @DisplayName("an unknown task is AgentRequestException(not_found)")
    suspend fun unknownTask(server: GameServer) {
        val error = thrown<AgentRequestException> { server.execute("e2e:no-such-task") }
        assertEquals("not_found", error.type)
    }
}
