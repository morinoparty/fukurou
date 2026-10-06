package party.morino.fukurou.e2e

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.fail
import party.morino.fukurou.event.BukkitEvents
import party.morino.fukurou.eventually
import party.morino.fukurou.image.assertMatches
import party.morino.fukurou.pause
import party.morino.fukurou.player.KeySym
import party.morino.fukurou.player.MouseButton
import party.morino.fukurou.repeat
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.server.execute
import party.morino.fukurou.state.EntityQuery
import party.morino.fukurou.state.PlayerSnapshot
import party.morino.fukurou.step
import party.morino.fukurou.world.BlockPos
import party.morino.fukurou.world.Location
import party.morino.fukurou.world.Worlds
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.math.hypot
import kotlin.time.Duration.Companion.seconds

/**
 * v3 のエージェント経由の機能を、本物の Paper とクライアントの入力と組み合わせて通す（v3 設計 §1・§2）。
 * Xvfb などが要るので CI の e2e ジョブだけで実行する（check には含めない）。
 *
 * 各テストは独立している。既定の Isolation.Reset が各テストの前にアリーナ（原点を中心に 32×32、地表 y=-61 は草、
 * その下は土 2 層と岩盤）を敷き直し、インベントリを空にし、Alice をサバイバルで (0.5, -60, -8.5) に戻す。
 * クライアントの入力は取りこぼすことがある（ウィンドウのフォーカスなど）ので、入力とその結果の確認は eventually で
 * まとめて再試行し、決まった時間の待ちには頼らない。
 */
@ExtendWith(V3Arena::class)
class V3Test {
    @Test
    @DisplayName("state() reflects given items and the hotbar slot selected with the number keys")
    suspend fun `inventory and hotbar`(arena: V3Arena) {
        val alice = arena.alice
        alice.give(DIAMOND, 3)
        val diamondSlot = eventually {
            val state = alice.state()
            assertEquals(3, diamonds(state), "diamonds in the inventory")
            state.inventory.indexOfFirst { it?.type == DIAMOND }
        }
        // 空のインベントリへの give はホットバー（0〜8）のどこかに入る
        assertTrue(diamondSlot in 0..8, "diamond slot $diamondSlot")
        // 数字キーの 1〜9 がスロット 0〜8。押したキーが届くまで押し直してよい（同じスロットを選ぶだけ）
        eventually {
            alice.selectHotbar(diamondSlot)
            val state = alice.state()
            assertEquals(diamondSlot, state.selectedSlot, "selected slot")
            assertEquals(DIAMOND, state.mainHand?.type, "main hand")
        }
        val empty = if (diamondSlot == 2) 4 else 2
        eventually {
            alice.selectHotbar(empty)
            val state = alice.state()
            assertEquals(empty, state.selectedSlot, "selected slot")
            assertNull(state.mainHand, "main hand after selecting an empty slot")
        }
    }

    @Test
    @DisplayName("holding W walks the player forward on the flat arena")
    suspend fun walking(arena: V3Arena) {
        val alice = arena.alice
        // 南（+z）を向いて地面の上に立たせる。1.5 秒歩いても（約 6.5 ブロック）アリーナの中に収まる
        alice.teleport(0.5, -60.0, -8.5, yaw = 0f, pitch = 0f)
        val start = eventually { alice.state().location.also { assertNear(Location(0.5, -60.0, -8.5), it) } }
        eventually(30.seconds, 1.seconds) {
            alice.holding(KeySym.W) { pause(1.5.seconds) }
            val now = alice.state().location
            val moved = hypot(now.x - start.x, now.z - start.z)
            assertTrue(moved >= 1.0, "Alice moved only $moved blocks (from $start to $now)")
        }
    }

    @Test
    @DisplayName("holding the left button breaks the dirt below the player and fires BlockBreakEvent")
    suspend fun `breaking a block`(arena: V3Arena, server: GameServer) {
        val alice = arena.alice
        // 既定のスロット（x=0.5+2i, z=-8.5）から離れた場所で掘る。掘った穴が次のテストのリセットの位置に残らないように
        val below = BlockPos(4, -61, 4)
        // 真下を土にする（手で 0.75 秒で掘れる）。その下は岩盤にし、押し続けても 1 ブロックしか掘れないようにする
        // （次のテストのリセットが土で敷き直す）
        server.fill(BlockPos(4, -63, 4), BlockPos(4, -62, 4), "minecraft:bedrock")
        server.setBlock(below, "minecraft:dirt")
        // ブロックの中央に立って真下を向く（pitch 90 なら少しずれても足元のブロックを狙う）
        alice.teleport(4.5, -60.0, 4.5, yaw = 0f, pitch = 90f)
        eventually { assertNear(Location(4.5, -60.0, 4.5), alice.state().location) }
        val event = eventually(40.seconds, 1.seconds) {
            server.events.expect(BukkitEvents.BLOCK_BREAK, 5.seconds, { it.player == "Alice" }) {
                alice.holdMouse(MouseButton.LEFT, 3.seconds)
            }
        }
        assertEquals("Alice", event.player)
        eventually { assertEquals(Key.key("minecraft:air"), server.block(below).type, "block at $below") }
    }

    @Test
    @DisplayName("AsyncChatEvent carries the chat message as a Component")
    suspend fun `chat event`(arena: V3Arena, server: GameServer) {
        val alice = arena.alice
        val event = server.events.expect(BukkitEvents.ASYNC_CHAT, 30.seconds, { it.player == "Alice" }) {
            alice.chat("hello v3")
        }
        assertEquals("Alice", event.player)
        // message() は Adventure 流のアクセサ。値は Component の JSON
        val message = event["message"] ?: fail("no message field: ${event.fields}")
        assertEquals("hello v3", plainText(message), "message: $message")
        assertNotNull(event["originalMessage"], "fields: ${event.fields}")
    }

    @Test
    @DisplayName("execute, step and repeat, worldState and entities see the joined player")
    suspend fun `server state`(arena: V3Arena, server: GameServer) {
        val alice = arena.alice
        val online = server.execute<List<String>>("e2e:online")
        assertTrue("Alice" in online, "online: $online")
        step("prepare the stage") {
            server.fill(BlockPos(-1, -61, -1), BlockPos(1, -61, 1), "minecraft:stone")
            server.time(6000)
        }
        val mark = server.mark()
        repeat(2) { index -> server.command("say fukurou v3 repeat $index") }
        server.awaitLog(Regex("""fukurou v3 repeat 1"""), after = mark)
        assertEquals(Key.key("minecraft:stone"), server.block(BlockPos(1, -61, 1)).type)

        val world = server.worldState()
        assertEquals(Worlds.OVERWORLD, world.key)
        assertTrue("Alice" in world.players, "players: ${world.players}")
        val players = server.entities(EntityQuery(type = Key.key("minecraft:player")))
        val entity = players.singleOrNull { it.uuid == alice.uuid } ?: fail("Alice is not among the players: $players")
        // リセットで (0.5, -60, -8.5) に戻っている
        assertNear(Location(0.5, -60.0, -8.5), entity.location)
    }

    @Test
    @DisplayName("a screenshot taken inside eventually is saved and matches a fresh baseline")
    suspend fun `screenshot baseline`(arena: V3Arena) {
        val alice = arena.alice
        // 地平線を斜め下に見て、空と地面の両方を写す
        alice.teleport(0.5, -60.0, -8.5, yaw = 0f, pitch = 30f)
        // チャンクの描画を待つ（記録される待ち）
        pause(2.seconds)
        // eventually の中の撮影は記録しない試行。撮り直しても同じ名前でよく、eventually のステップに結びつく
        val shot = eventually {
            alice.screenshot("view").also { assertTrue(it.width > 0 && it.height > 0, "size ${it.width}x${it.height}") }
        }
        assertTrue(shot.path.exists(), "screenshot file ${shot.path}")
        // 基準画像はスクリーンショットの隣（出力ディレクトリの中）。無ければ書いて成功する
        val baseline = shot.path.resolveSibling("${shot.name}.baseline.png")
        Files.deleteIfExists(baseline)
        shot.assertMatches(baseline)
        assertTrue(baseline.exists(), "baseline $baseline was not written")
        // 同じ画像どうしなら一致する
        shot.assertMatches(baseline)
    }

    /** インベントリのダイヤモンドの数。 */
    private fun diamonds(state: PlayerSnapshot): Int =
        state.inventory.filterNotNull().filter { it.type == DIAMOND }.sumOf { it.amount }

    /** 座標が 0.5 ブロック以内で一致する（向きと次元は見ない）。 */
    private fun assertNear(expected: Location, actual: Location) {
        val distance = hypot(hypot(actual.x - expected.x, actual.y - expected.y), actual.z - expected.z)
        assertTrue(distance <= 0.5, "expected near $expected, got $actual")
    }

    /** Component の JSON のプレーンテキスト（text と extra をたどる。文字列だけの Component もある）。 */
    private fun plainText(json: JsonElement): String =
        when (json) {
            is JsonNull -> ""
            is JsonPrimitive -> json.content
            is JsonArray -> json.joinToString("") { plainText(it) }
            is JsonObject -> {
                val text = (json["text"] as? JsonPrimitive)?.content.orEmpty()
                val extra = (json["extra"] as? JsonArray)?.joinToString("") { plainText(it) }.orEmpty()
                text + extra
            }
        }

    /** 定数。 */
    private companion object {
        /** ダイヤモンドのキー。 */
        val DIAMOND: Key = Key.key("minecraft:diamond")
    }
}
