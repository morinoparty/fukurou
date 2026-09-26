package party.morino.fukurou.e2e

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.pause
import party.morino.fukurou.screenshot
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.world.BlockPos
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * 本物の Paper と 2 つのクライアントで、コマンド・チャット・ログの待ち・Adventure の送信・スクリーンショットを一通り通す。
 * Xvfb などが要るので CI の kotlin-e2e ジョブだけで実行する（check には含めない）。
 *
 * テストは 2 件。同じサーバーを使い回し、テストの間のリセットも通す。
 * result.json（全テストが passed であること）は、JVM が書き終えた後に CI のステップが確かめる。
 */
@ExtendWith(SmokeArena::class)
class SmokeTest {
    @Test
    @DisplayName("runs server commands, chat and chat-bar commands and waits for the logs")
    suspend fun `commands and chat`(arena: SmokeArena, server: GameServer) {
        val alice = arena.alice
        val bob = arena.bob
        // RCON のコマンドと、その後のサーバーログの待ち
        val mark = server.mark()
        server.command("say fukurou smoke")
        server.awaitLog(Regex("""fukurou smoke"""), after = mark)
        // チャット欄からの発言がサーバーに届き、もう 1 人のクライアントにも表示される
        val bobMark = bob.mark()
        alice.chat("hello from alice")
        server.awaitLog(Regex("""<Alice> hello from alice"""), timeout = 20.seconds)
        bob.awaitChat(Regex("""<Alice> hello from alice"""), timeout = 20.seconds, after = bobMark)
        // チャット欄からのコマンド（op の Alice）。サーバーログの「issued server command」を待つ
        alice.sendCommand("time set noon")
        // Adventure の Audience としてプレイヤーに送り、クライアントログで受け取りを確かめる
        val message = Component.text("hello from fukurou", NamedTextColor.GREEN)
        bob.sendMessage(message)
        bob.awaitChat(message)
    }

    @Test
    @DisplayName("teleports both players, shows a title and takes simultaneous screenshots")
    suspend fun `teleport and screenshots`(arena: SmokeArena, server: GameServer) {
        val alice = arena.alice
        val bob = arena.bob
        // 足場を置き、スクリーンショットが奈落を向かないようにする（examples/fukurou.yml の platform と同じ）
        server.fill(BlockPos(-3, -61, -3), BlockPos(3, -61, 3), "minecraft:stone")
        server.time(6000)
        server.weatherClear()
        // 2 人を足場の上で向かい合わせる
        alice.teleport(-1.5, -60.0, 0.5, yaw = -90f, pitch = 0f)
        bob.teleport(2.5, -60.0, 0.5, yaw = 90f, pitch = 0f)
        // Audience として両方にタイトルを出す（GameServer は全プレイヤーへ転送する ForwardingAudience）
        server.showTitle(Title.title(Component.text("fukurou"), Component.text("smoke")))
        // チャンクの描画とタイトルの表示を待つ（記録される待ち）
        pause(2.seconds)
        // 2 人を同時に撮る。戻り値は引数の順
        val shots = screenshot(alice, bob, name = "both")
        assertEquals(listOf("Alice", "Bob"), shots.map { it.player })
        shots.forEach { shot ->
            assertTrue(shot.path.exists(), "screenshot file ${shot.path}")
            assertTrue(shot.width > 0 && shot.height > 0, "screenshot size ${shot.width}x${shot.height}")
        }
    }
}
