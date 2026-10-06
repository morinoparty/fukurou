package party.morino.fukurou.engine.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.engine.test.StepHost
import party.morino.fukurou.engine.test.TestRun
import party.morino.fukurou.error.AgentRequestException
import party.morino.fukurou.error.ClientDiedException
import party.morino.fukurou.error.FukurouException
import party.morino.fukurou.error.HarnessTimeoutException
import party.morino.fukurou.error.ServerTaskException
import party.morino.fukurou.error.ServerUnavailableException
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.error.UnsupportedCapabilityException
import party.morino.fukurou.event.EventAssertionError
import party.morino.fukurou.result.RunRecorder
import party.morino.fukurou.result.event.PlannedTest
import party.morino.fukurou.result.model.enums.SessionKind
import party.morino.fukurou.result.model.enums.StepStatus
import party.morino.fukurou.result.model.run.FukurouInfo
import party.morino.fukurou.result.model.run.MinecraftInfo
import party.morino.fukurou.result.model.step.StepResult
import party.morino.fukurou.result.model.suite.SelectionInfo
import party.morino.fukurou.spi.model.AgentEndpoint
import party.morino.fukurou.state.EntityQuery
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.Location
import party.morino.fukurou.world.Worlds
import java.net.ServerSocket
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AgentAccessTest {
    /** Minecraft を使わないサーバーの代わり（ParallelRunnerTest と同じ）。 */
    private class FakeHost : StepHost {
        override val resultId: String = "paper-1.21.4-agent"
        val recorder = RunRecorder(resultId, "test", MinecraftInfo("1.21.4"), FukurouInfo("dev", "5.0.4"), null, SelectionInfo(), writer = null)
        override val observer: RunRecorder get() = recorder
        override val harnessScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val warnings = mutableListOf<String>()

        override fun log(message: String) {}

        override fun warn(message: String) {
            warnings += message
        }

        override fun checkClientsAlive() {}

        override fun deadClient(): ClientDiedException? = null

        override fun serverDied(error: ServerUnavailableException) {}
    }

    private val host = FakeHost()

    /** サーバーのプロセスが終わったことにする。 */
    @Volatile
    private var serverDead = false

    private val agents = mutableListOf<FakeAgent>()

    @AfterEach
    fun tearDown() {
        agents.forEach { it.close() }
        host.harnessScope.cancel()
    }

    /** 偽のエージェントを起動する。 */
    private fun agent(token: String = FakeAgent.TOKEN, port: Int = 0, handler: FakeAgent.Connection.(JsonObject) -> Unit = { reply(it) }): FakeAgent =
        FakeAgent(token, port, handler).also { agents += it }

    /** 待ちの間隔を縮めた接続。 */
    private fun access(): AgentAccess = AgentAccess(
        host = host,
        typeId = "paper",
        liveness = ::checkServer,
        checkServer = ::checkServer,
        pollInterval = 20.milliseconds,
        retryInterval = 50.milliseconds,
        reconnectTimeout = 2.seconds,
        grace = 100.milliseconds,
    )

    private fun checkServer() {
        if (serverDead) throw ServerUnavailableException("paper-1.21.4-agent is not running (exited with code 1)")
    }

    /** 接続済みの AgentAccess。 */
    private suspend fun connected(agent: FakeAgent): AgentAccess =
        access().also { it.connect(AgentEndpoint(agent.port, FakeAgent.TOKEN), 5.seconds) }

    /** テスト t の中で block を実行する（ステップを記録する）。 */
    private fun <T> inTest(timeout: Duration = 1.minutes, block: suspend () -> T): T {
        val index = testsStarted++
        if (index == 0) {
            host.recorder.runPlanned(TEST_IDS.mapIndexed { order, id -> PlannedTest("A", id, id, id, emptyList(), order, "junit:A#$id", "$order") })
            host.recorder.sessionStarted(0, SessionKind.INITIAL)
        }
        val id = TEST_IDS[index]
        host.recorder.testStarted(id, 0, emptyList())
        val run = TestRun(host, id, 0, timeout)
        return runBlocking(StepScope(run)) { block() }
    }

    /** inTest を呼んだ回数。 */
    private var testsStarted = 0

    /** 1 つ目のテストに記録されたステップ。 */
    private fun steps(): List<StepResult> = host.recorder.build().tests.first().steps

    private companion object {
        /** 1 つの単体テストの中で始める偽のテストの id。 */
        private val TEST_IDS = listOf("t", "t2")
    }

    /** 応答を op ごとに決めた handler。 */
    private fun answering(results: Map<String, String>): FakeAgent.Connection.(JsonObject) -> Unit = { request ->
        val result = results[request.op]
        if (result != null) ok(request.id, Json.parseToJsonElement(result)) else reply(request)
    }

    // --- 接続 --------------------------------------------------------------------

    @Test
    @DisplayName("Connects with the token and answers a query, recording a server step")
    fun helloAndQuery() {
        val agent = agent()
        val tick = inTest {
            val access = connected(agent)
            access.currentTick()
        }
        assertEquals(100L, tick)
        assertEquals(1, agent.hellos)
        val step = steps().single()
        assertEquals(listOf("server", "query", "tick"), listOf(step.on, step.action, step.label))
        assertEquals(StepStatus.PASSED, step.status)
    }

    @Test
    @DisplayName("A rejected token fails the start immediately with SetupException")
    fun authFailure() {
        val agent = agent(token = "another")
        val started = System.nanoTime()
        val error = assertThrows<SetupException> { runBlocking { access().connect(AgentEndpoint(agent.port, FakeAgent.TOKEN), 30.seconds) } }
        assertTrue("auth" in error.message.orEmpty(), error.message)
        assertTrue(System.nanoTime() - started < 10_000_000_000L, "auth failure must not wait for the timeout")
    }

    @Test
    @DisplayName("Retries hello until the agent listens, and gives up with SetupException after the timeout")
    fun retryUntilListening() {
        val port = ServerSocket(0).use { it.localPort }
        // 誰も待ち受けていなければ時間切れ
        val error = assertThrows<SetupException> { runBlocking { access().connect(AgentEndpoint(port, FakeAgent.TOKEN), 300.milliseconds) } }
        assertTrue("did not answer" in error.message.orEmpty(), error.message)
        // 少し後に待ち受けを始めれば、再試行で通る
        val access = access()
        runBlocking {
            val late = async(Dispatchers.IO) {
                delay(300.milliseconds)
                agent(port = port)
            }
            access.connect(AgentEndpoint(port, FakeAgent.TOKEN), 5.seconds)
            late.await()
        }
        assertEquals(100L, runBlocking { access.currentTick() })
    }

    @Test
    @DisplayName("Stops retrying when the server process exits")
    fun connectServerDied() {
        val port = ServerSocket(0).use { it.localPort }
        serverDead = true
        assertThrows<ServerUnavailableException> { runBlocking { access().connect(AgentEndpoint(port, FakeAgent.TOKEN), 5.seconds) } }
    }

    @Test
    @DisplayName("Without an agent every call is UnsupportedCapabilityException(type, agent)")
    fun unsupported() {
        val access = access()
        runBlocking { access.connect(null, 1.seconds) }
        val error = assertThrows<UnsupportedCapabilityException> { runBlocking { access.currentTick() } }
        assertEquals("paper", error.typeId)
        assertEquals("agent", error.capability)
        assertThrows<UnsupportedCapabilityException> { runBlocking { access.events.record("BlockBreakEvent") } }
        assertThrows<UnsupportedCapabilityException> { runBlocking { access.execute("x", null, 1.seconds) } }
        // 切断した後も同じ
        val agent = agent()
        val connected = runBlocking { connected(agent) }
        connected.disconnect()
        assertThrows<UnsupportedCapabilityException> { runBlocking { connected.worldState(Worlds.OVERWORLD) } }
    }

    // --- 状態の取得 ------------------------------------------------------------------

    @Test
    @DisplayName("Decodes block, world, entities and player snapshots and sends the documented arguments")
    fun decodesSnapshots() {
        val uuid = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        val location = """{"world":"minecraft:overworld","x":0.5,"y":-60.0,"z":1.5,"yaw":90.0,"pitch":10.0}"""
        val inventory = (0 until 41).joinToString(",", "[", "]") { slot ->
            when (slot) {
                0 -> """{"type":"minecraft:diamond_sword","amount":1,"name":{"text":"Edge","color":"gold"},"lore":[{"text":"sharp"}],""" +
                    """"customModelData":7,"enchantments":{"minecraft:sharpness":5},"damage":3,"unbreakable":true}"""
                40 -> """{"type":"minecraft:shield","amount":1,"name":null,"lore":[],"customModelData":null,"enchantments":{},"damage":0,"unbreakable":false}"""
                else -> "null"
            }
        }
        val agent = agent(
            handler = answering(
                mapOf(
                    "block" to """{"world":"minecraft:the_nether","x":1,"y":2,"z":3,"type":"minecraft:oak_stairs","data":"minecraft:oak_stairs[facing=east]"}""",
                    "world" to """{"key":"minecraft:overworld","name":"world","time":6000,"fullTime":30000,"storm":true,"thundering":false,""" +
                        """"difficulty":"peaceful","players":["Alice"]}""",
                    "entities" to """[{"uuid":"$uuid","type":"minecraft:armor_stand","location":$location,"name":{"text":"Armor Stand"},""" +
                        """"customName":null,"tags":["fukurou"],"health":20.0,"dead":false}]""",
                    "player" to """{"name":"Alice","uuid":"$uuid","location":$location,"gameMode":"creative","health":19.5,"maxHealth":20.0,""" +
                        """"food":18,"saturation":4.5,"level":3,"exp":0.25,"flying":true,"sneaking":false,"sprinting":true,"op":true,""" +
                        """"selectedSlot":0,"inventory":$inventory,""" +
                        """"openInventory":{"type":"CHEST","title":{"text":"Chest"},"size":27,"contents":[null,""" +
                        """{"type":"minecraft:stone","amount":64,"name":null,"lore":[],"customModelData":null,"enchantments":{},"damage":null,"unbreakable":false}]},""" +
                        """"effects":[{"type":"minecraft:speed","amplifier":1,"duration":200}],"tags":["a","b"],"locale":"ja_jp"}""",
                ),
            ),
        )
        inTest {
            val access = connected(agent)
            val block = access.block(BlockPos(1, 2, 3), Worlds.NETHER)
            assertEquals(Key.key("minecraft:oak_stairs"), block.type)
            assertEquals("minecraft:oak_stairs[facing=east]", block.data)
            assertEquals(Worlds.NETHER, block.world)
            assertEquals(listOf(1, 2, 3), listOf(block.x, block.y, block.z))

            val world = access.worldState(Worlds.OVERWORLD)
            assertEquals(6000L, world.time)
            assertEquals(30000L, world.fullTime)
            assertTrue(world.storm)
            assertEquals(listOf("Alice"), world.players)

            val near = Location(0.0, -60.0, 0.0, world = Worlds.NETHER)
            val entities = access.entities(EntityQuery(type = Key.key("minecraft:armor_stand"), near = near, radius = 8.0, tag = "fukurou"))
            val stand = entities.single()
            assertEquals(UUID.fromString(uuid), stand.uuid)
            assertEquals(Component.text("Armor Stand"), stand.name)
            assertNull(stand.customName)
            assertEquals(setOf("fukurou"), stand.tags)
            assertEquals(Location(0.5, -60.0, 1.5, 90f, 10f, Worlds.OVERWORLD), stand.location)

            val player = access.player("Alice")
            assertEquals("creative", player.gameMode)
            assertEquals(19.5, player.health)
            assertEquals(4.5f, player.saturation)
            assertEquals(41, player.inventory.size)
            val sword = player.mainHand!!
            assertEquals(Component.text("Edge", NamedTextColor.GOLD), sword.name)
            assertEquals(listOf(Component.text("sharp")), sword.lore)
            assertEquals(mapOf(Key.key("minecraft:sharpness") to 5), sword.enchantments)
            assertEquals(7, sword.customModelData)
            assertTrue(sword.unbreakable)
            assertEquals(Key.key("minecraft:shield"), player.offHand!!.type)
            val chest = player.openInventory!!
            assertEquals("CHEST", chest.type)
            assertEquals(64, chest.contents[1]!!.amount)
            assertNull(chest.contents[0])
            assertEquals(Key.key("minecraft:speed"), player.effects.single().type)
            assertEquals(setOf("a", "b"), player.tags)
            assertEquals("ja_jp", player.locale)
        }
        // 引数
        val byOp = agent.requests.associateBy { it.op }
        assertEquals("minecraft:the_nether", byOp["block"]!!["world"]!!.jsonPrimitive.content)
        assertEquals(3, byOp["block"]!!["z"]!!.jsonPrimitive.int)
        val entities = byOp["entities"]!!
        assertEquals("minecraft:armor_stand", entities["type"]!!.jsonPrimitive.content)
        // world が無ければ near の world を使う
        assertEquals("minecraft:the_nether", entities["world"]!!.jsonPrimitive.content)
        assertEquals("8.0", entities["near"]!!.jsonObject["radius"]!!.jsonPrimitive.content)
        assertEquals("fukurou", entities["tag"]!!.jsonPrimitive.content)
        assertEquals(256, entities["limit"]!!.jsonPrimitive.int)
        assertEquals("Alice", byOp["player"]!!["name"]!!.jsonPrimitive.content)
        // ステップ
        assertEquals(
            listOf(
                "block 1 2 3 in minecraft:the_nether",
                "world minecraft:overworld",
                "entities type=minecraft:armor_stand world=minecraft:the_nether near=0.0,-60.0,0.0 radius=8.0 tag=fukurou",
                "player Alice",
            ),
            steps().map { it.label },
        )
        assertTrue(steps().all { it.on == "server" && it.action == "query" })
    }

    @Test
    @DisplayName("A malformed snapshot is a harness error, not a test failure")
    fun malformedSnapshot() {
        val agent = agent(handler = answering(mapOf("block" to """{"world":"minecraft:overworld","x":1}""")))
        val error = assertThrows<FukurouException> { inTest { connected(agent).block(BlockPos(1, 2, 3), Worlds.OVERWORLD) } }
        assertTrue("unexpected shape" in error.message.orEmpty(), error.message)
    }

    @Test
    @DisplayName("awaitTicks sends the tick count with a budget and records wait_ticks")
    fun awaitTicks() {
        val agent = agent(handler = answering(mapOf("awaitTicks" to """{"tick":105}""")))
        inTest { connected(agent).awaitTicks(5) }
        val request = agent.requests.single()
        assertEquals(5, request["ticks"]!!.jsonPrimitive.int)
        // timeoutMs は遅れの余裕だけ（tick の時間はエージェントが足す）
        assertEquals(AgentAccess.AWAIT_TICKS_SLACK.inWholeMilliseconds, request["timeoutMs"]!!.jsonPrimitive.content.toLong())
        assertEquals(listOf("wait_ticks", "5"), steps().single().let { listOf(it.action, it.label) })
        assertThrows<IllegalArgumentException> { runBlocking { access().awaitTicks(0) } }
    }

    @Test
    @DisplayName("awaitTicks waits longer than the agent does, even for the largest tick count")
    fun awaitTicksWaitsLongerThanTheAgent() {
        val grace = 10.seconds
        for (ticks in listOf(1, 200, 201, 1200, 72_000)) {
            // エージェントの待ち（Operations.awaitTicks）: ticks × 50 ms + timeoutMs
            val agentWait = (ticks * 50L).milliseconds + AgentAccess.AWAIT_TICKS_SLACK
            val clientWait = AgentAccess.awaitTicksWait(ticks, grace)
            assertTrue(clientWait > agentWait, "ticks=$ticks: client $clientWait <= agent $agentWait")
            assertEquals(grace, clientWait - agentWait)
        }
    }

    // --- execute とエラーの読み替え -------------------------------------------------------

    @Test
    @DisplayName("execute sends the task, args and timeout and returns the value (JsonNull for null)")
    fun execute() {
        val agent = agent { request ->
            when (request["task"]?.jsonPrimitive?.content) {
                "echo" -> ok(request.id, buildJsonObject { put("value", request["args"]!!) })
                else -> ok(request.id, buildJsonObject { put("value", JsonNull) })
            }
        }
        val (echoed, nothing) = inTest {
            val access = connected(agent)
            access.execute("echo", buildJsonObject { put("n", 3) }, 5.seconds) to access.execute("void", null, 5.seconds)
        }
        assertEquals(3, echoed.jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, nothing)
        val first = agent.requests.first()
        assertEquals("echo", first["task"]!!.jsonPrimitive.content)
        assertEquals(5000, first["timeoutMs"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, agent.requests[1]["args"])
        assertEquals(listOf("execute" to "echo", "execute" to "void"), steps().map { it.action to it.label })
    }

    @Test
    @DisplayName("Maps every agent error type to the documented exception")
    fun errorMapping() {
        val agent = agent { request ->
            when (val task = request["task"]?.jsonPrimitive?.content) {
                "boom" -> error(request.id, "task_failed", "broken", "java.lang.IllegalStateException", "java.lang.IllegalStateException: broken\n\tat X")
                null -> reply(request)
                else -> error(request.id, task, "$task happened")
            }
        }
        inTest {
            val access = connected(agent)
            val failed = assertThrows<ServerTaskException> { runBlocking { access.execute("boom", null, 5.seconds) } }
            assertEquals("boom", failed.task)
            assertEquals("java.lang.IllegalStateException", failed.remoteType)
            assertTrue(failed.remoteStackTrace!!.contains("at X"))
            for (type in listOf("not_found", "bad_request", "auth")) {
                val error = assertThrows<AgentRequestException> { runBlocking { access.execute(type, null, 5.seconds) } }
                assertEquals(type, error.type)
                assertTrue("$type happened" in error.message.orEmpty())
            }
            assertThrows<HarnessTimeoutException> { runBlocking { access.execute("timeout", null, 5.seconds) } }
            val internal = assertThrows<FukurouException> { runBlocking { access.execute("internal", null, 5.seconds) } }
            assertTrue("internal" in internal.message.orEmpty())
        }
        assertTrue(steps().all { it.status == StepStatus.FAILED })
    }

    @Test
    @DisplayName("A silent agent times out with HarnessTimeoutException after the wait")
    fun silentAgent() {
        val agent = agent { }
        val access = runBlocking { connected(agent) }
        val error = assertThrows<HarnessTimeoutException> { runBlocking { access.execute("slow", null, 50.milliseconds) } }
        assertTrue("did not answer" in error.message.orEmpty(), error.message)
    }

    @Test
    @DisplayName("Parallel lanes share one connection and each gets its own answer")
    fun parallelRequests() {
        val agent = agent { request ->
            // 逆順に答えても id で対応づく
            Thread.ofVirtual().start {
                Thread.sleep((10 - request["args"]!!.jsonPrimitive.int).toLong() * 5)
                ok(request.id, buildJsonObject { put("value", request["args"]!!) })
            }
        }
        val access = runBlocking { connected(agent) }
        val values = runBlocking(Dispatchers.IO) {
            (0 until 10).map { n -> async { access.execute("n", JsonPrimitive(n), 5.seconds).jsonPrimitive.int } }.awaitAll()
        }
        assertEquals((0 until 10).toList(), values)
        assertEquals(1, agent.connections.size)
    }

    // --- 接続の切断 -----------------------------------------------------------------

    @Test
    @DisplayName("A lost connection with a dead server process is ServerUnavailableException")
    fun lostWithDeadServer() {
        val agent = agent { request ->
            serverDead = true
            close()
        }
        val access = runBlocking { connected(agent) }
        val error = assertThrows<ServerUnavailableException> { runBlocking { access.currentTick() } }
        assertTrue("not running" in error.message.orEmpty(), error.message)
        assertEquals(1, agent.hellos)
    }

    @Test
    @DisplayName("A lost connection with a live server reconnects once, resubscribes and retries the query")
    fun reconnects() {
        var dropped = false
        val agent = agent { request ->
            if (request.op == "ping" && !dropped) {
                dropped = true
                close()
            } else {
                reply(request)
            }
        }
        val access = runBlocking { connected(agent) }
        val recorder = runBlocking { access.events.record("BlockBreakEvent") }
        assertEquals(100L, runBlocking { access.currentTick() })
        assertEquals(2, agent.hellos)
        // 新しい接続で購読し直している
        val deadline = System.nanoTime() + 2_000_000_000L
        while (agent.last.subscriptions.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf(1), agent.last.subscriptions)
        agent.last.event(1, "org.bukkit.event.block.BlockBreakEvent")
        runBlocking { recorder.await(2.seconds) }
        assertTrue(host.warnings.any { "reconnected" in it })
    }

    @Test
    @DisplayName("A lost connection that cannot be re-established is ServerUnavailableException")
    fun reconnectFails() {
        val agent = agent { request ->
            agent.stopListening()
            close()
        }
        val access = runBlocking { connected(agent) }
        val error = assertThrows<ServerUnavailableException> { runBlocking { access.currentTick() } }
        assertTrue("could not reconnect" in error.message.orEmpty(), error.message)
    }

    @Test
    @DisplayName("execute is not sent twice after a reconnect")
    fun executeNotRetried() {
        val agent = agent { request ->
            if (request.op == "execute") close() else reply(request)
        }
        val access = runBlocking { connected(agent) }
        val error = assertThrows<AgentRequestException> { runBlocking { access.execute("once", null, 5.seconds) } }
        assertEquals("connection_lost", error.type)
        assertEquals(1, agent.requests.count { it.op == "execute" })
    }

    // --- イベント ------------------------------------------------------------------

    /** subscribe に答える前に、その購読へイベントを 1 件送るエージェント。 */
    private fun eagerAgent(): FakeAgent = agent { request ->
        if (request.op == "subscribe") {
            val subscription = request["subscription"]!!.jsonPrimitive.int
            // 応答より先にイベントが届いても取りこぼさない
            event(subscription, "org.bukkit.event.block.BlockBreakEvent", tick = 7, fields = buildJsonObject { put("player", "Alice") })
            ok(request.id, buildJsonObject { put("types", Json.parseToJsonElement("""["org.bukkit.event.block.BlockBreakEvent"]""")) })
        } else {
            reply(request)
        }
    }

    @Test
    @DisplayName("record resolves the types, keeps events that arrive before the subscribe answer, and await includes them")
    fun recorderAwait() {
        val agent = eagerAgent()
        inTest {
            val access = connected(agent)
            val recorder = access.events.record("BlockBreakEvent")
            assertEquals(listOf("org.bukkit.event.block.BlockBreakEvent"), recorder.types)
            val event = recorder.await(1.seconds) { it.player == "Alice" }
            assertEquals(7L, event.tick)
            assertEquals("BlockBreakEvent", event.simpleName)
            assertEquals(1, recorder.events().size)
            // 2 件目は後から届く
            Thread.ofVirtual().start {
                Thread.sleep(100)
                agent.last.event(1, "org.bukkit.event.block.BlockBreakEvent", tick = 9, fields = buildJsonObject { put("player", "Bob") })
            }
            val two = recorder.awaitCount(2, 2.seconds)
            assertEquals(listOf(7L, 9L), two.map { it.tick })
            recorder.close()
            recorder.close()
        }
        val request = agent.requests.first()
        assertEquals("subscribe", request.op)
        assertEquals(listOf("BlockBreakEvent"), request["types"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("subscribe" to "BlockBreakEvent", "await_event" to "org.bukkit.event.block.BlockBreakEvent"),
            steps().take(2).map { it.action to it.label },
        )
        assertEquals("await_event", steps()[2].action)
        // close は 1 回だけ unsubscribe を送る
        val deadline = System.nanoTime() + 2_000_000_000L
        while (agent.requests.none { it.op == "unsubscribe" } && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, agent.requests.count { it.op == "unsubscribe" })
    }

    @Test
    @DisplayName("A recorder wait that runs out is EventAssertionError; the test deadline is HarnessTimeoutException")
    fun recorderTimeouts() {
        val agent = agent()
        inTest {
            val access = connected(agent)
            val recorder = access.events.record("BlockBreakEvent")
            val error = assertThrows<EventAssertionError> { runBlocking { recorder.await(200.milliseconds) } }
            assertTrue("no matching" in error.message.orEmpty(), error.message)
        }
        val short = agent()
        assertThrows<HarnessTimeoutException> {
            inTest(timeout = 2.seconds) {
                val access = connected(short)
                val recorder = access.events.record("BlockBreakEvent")
                recorder.await(1.minutes)
            }
        }
    }

    @Test
    @DisplayName("ServerEvents.await only sees events after the call")
    fun serverEventsAwait() {
        // 購読のたびに古いイベント（tick 1）は送らず、購読の後に新しいもの（tick 2）だけを送る
        val agent = agent { request ->
            reply(request)
            if (request.op == "subscribe" && request["subscription"]!!.jsonPrimitive.int == 2) {
                event(2, "org.bukkit.event.server.ServerCommandEvent", tick = 2, fields = buildJsonObject { put("command", "say hi") })
            }
        }
        inTest {
            val access = connected(agent)
            // 1 つ目の購読（記録器）には古いイベントを送る。ServerEvents.await はそれを見ない
            val recorder = access.events.record("ServerCommandEvent")
            agent.last.event(1, "org.bukkit.event.server.ServerCommandEvent", tick = 1, fields = buildJsonObject { put("command", "say old") })
            recorder.await(1.seconds)
            val event = access.events.await("ServerCommandEvent", 2.seconds) { it.string("command")!!.contains("say") }
            assertEquals(2L, event.tick)
            val error = assertThrows<EventAssertionError> { runBlocking { access.events.await("ServerCommandEvent", 200.milliseconds) } }
            assertTrue("no matching" in error.message.orEmpty())
        }
        // 一時的な購読は閉じる
        val deadline = System.nanoTime() + 2_000_000_000L
        while (agent.requests.count { it.op == "unsubscribe" } < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf(2, 3), agent.requests.filter { it.op == "unsubscribe" }.map { it["subscription"]!!.jsonPrimitive.int })
    }

    @Test
    @DisplayName("expect subscribes before running the action and records subscribe, the action and await_event in order")
    fun expectOrdering() {
        val agent = agent { request ->
            reply(request)
            if (request.op == "execute") {
                // 操作が起こしたイベント（購読済みの番号へ）
                subscriptions.forEach { event(it, "org.bukkit.event.block.BlockPlaceEvent", tick = 3) }
            }
        }
        val event = inTest {
            val access = connected(agent)
            access.events.expect("BlockPlaceEvent", 2.seconds) { access.execute("place", null, 5.seconds) }
        }
        assertEquals(3L, event.tick)
        assertEquals(listOf("subscribe", "execute"), agent.requests.take(2).map { it.op })
        assertEquals(listOf("subscribe", "execute", "await_event"), steps().map { it.action })
    }

    @Test
    @DisplayName("assertNone and assertNoneWithin fail on a matching event and pass otherwise")
    fun negativeChecks() {
        val agent = eagerAgent()
        inTest {
            val access = connected(agent)
            val recorder = access.events.record("BlockBreakEvent")
            assertThrows<EventAssertionError> { recorder.assertNone() }
            recorder.assertNone { it.player == "Bob" }
            // 期間の間に Bob のイベントが届けば失敗
            Thread.ofVirtual().start {
                Thread.sleep(100)
                agent.last.event(1, "org.bukkit.event.block.BlockBreakEvent", fields = buildJsonObject { put("player", "Bob") })
            }
            val started = System.nanoTime()
            assertThrows<EventAssertionError> { runBlocking { recorder.assertNoneWithin(5.seconds) { it.player == "Bob" } } }
            assertTrue(System.nanoTime() - started < 4_000_000_000L, "fails as soon as the event arrives")
            recorder.assertNoneWithin(200.milliseconds) { it.player == "Carol" }
        }
        val actions = steps().map { it.action to it.status }
        assertEquals(
            listOf(
                "subscribe" to StepStatus.PASSED,
                "assert_no_event" to StepStatus.FAILED,
                "assert_no_event" to StepStatus.PASSED,
                "assert_no_event" to StepStatus.FAILED,
                "assert_no_event" to StepStatus.PASSED,
            ),
            actions,
        )
        assertEquals("BlockBreakEvent 0.2s", steps().last().label.replace("org.bukkit.event.block.", ""))
    }

    @Test
    @DisplayName("closeRecorders closes open recorders; later events are dropped")
    fun closeRecorders() {
        val agent = eagerAgent()
        val access = runBlocking { connected(agent) }
        val recorder = runBlocking { access.events.record("BlockBreakEvent") }
        access.closeRecorders()
        agent.last.event(1, "org.bukkit.event.block.BlockBreakEvent", tick = 50)
        Thread.sleep(100)
        assertEquals(listOf(7L), recorder.events().map { it.tick })
        val deadline = System.nanoTime() + 2_000_000_000L
        while (agent.requests.none { it.op == "unsubscribe" } && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, agent.requests.count { it.op == "unsubscribe" })
    }

    @Test
    @DisplayName("An unknown event type is AgentRequestException(not_found) and registers nothing")
    fun unknownEventType() {
        val agent = agent { request ->
            if (request.op == "subscribe") error(request.id, "not_found", "no event class NoSuchEvent") else reply(request)
        }
        val access = runBlocking { connected(agent) }
        val error = assertThrows<AgentRequestException> { runBlocking { access.events.record("NoSuchEvent") } }
        assertEquals("not_found", error.type)
        access.closeRecorders()
        Thread.sleep(50)
        assertTrue(agent.requests.none { it.op == "unsubscribe" })
    }

    @Test
    @DisplayName("A caller's cancellation does not close the shared connection")
    fun cancellationKeepsConnection() {
        val agent = agent { request -> if (request.op != "execute") reply(request) }
        val access = runBlocking { connected(agent) }
        val result = runBlocking { withTimeoutOrNull(100.milliseconds) { access.execute("never", null, 5.seconds) } }
        assertNull(result)
        assertEquals(100L, runBlocking { access.currentTick() })
        assertEquals(1, agent.hellos)
    }

    @Test
    @DisplayName("A subscribe abandoned by the caller is unsubscribed so the agent does not keep a listener")
    fun abandonedSubscribe() {
        // subscribe を登録したことにして、応答を返さない
        val agent = agent { request ->
            if (request.op == "subscribe") subscriptions += request["subscription"]!!.jsonPrimitive.int else reply(request)
        }
        val access = runBlocking { connected(agent) }
        val recorder = runBlocking { withTimeoutOrNull(100.milliseconds) { access.events.record("BlockBreakEvent") } }
        assertNull(recorder)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (agent.requests.none { it.op == "unsubscribe" } && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf(1), agent.requests.filter { it.op == "unsubscribe" }.map { it["subscription"]!!.jsonPrimitive.int })
        // 後から届いたイベントは誰にも渡らず、接続はそのまま使える
        agent.last.event(1, "org.bukkit.event.block.BlockBreakEvent")
        assertEquals(100L, runBlocking { access.currentTick() })
        assertEquals(1, agent.hellos)
    }

    @Test
    @DisplayName("A request in flight when the session stops is ServerUnavailableException, not UnsupportedCapabilityException")
    fun disconnectDuringRequest() {
        val agent = agent { request -> if (request.op != "execute") reply(request) }
        val access = runBlocking { connected(agent) }
        val error = assertThrows<ServerUnavailableException> {
            runBlocking {
                launch(Dispatchers.IO) {
                    while (agent.requests.none { it.op == "execute" }) delay(10.milliseconds)
                    access.disconnect()
                }
                access.execute("never", null, 30.seconds)
            }
        }
        assertTrue("session was stopped" in error.message.orEmpty(), error.message)
        // 張り直していない
        assertEquals(1, agent.hellos)
    }

    @Test
    @DisplayName("A request over 16 MiB is refused before sending and leaves the connection usable")
    fun oversizedRequest() {
        val agent = agent()
        val access = runBlocking { connected(agent) }
        val huge = JsonPrimitive("x".repeat(AgentProtocol.MAX_LINE_BYTES))
        assertThrows<IllegalArgumentException> { runBlocking { access.execute("big", huge, 5.seconds) } }
        assertTrue(agent.requests.none { it.op == "execute" })
        assertEquals(100L, runBlocking { access.currentTick() })
    }
}
