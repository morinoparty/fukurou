package party.morino.fukurou.e2e

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.pause
import party.morino.fukurou.player.Player
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.world.BlockPos
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

/**
 * 1 つのテストクラスに 2 種類の拡張（LobbyArena と DuelArena）を登録し、本物の Paper を 2 台同時に動かす。
 * Xvfb などが要るので CI の kotlin-e2e ジョブだけで実行する（check には含めない）。
 *
 * 拡張が 2 つあると GameServer の引数はどちらか決まらないので、拡張の型で受け取る（§5.4）。
 * 1 件のテストが両方のサーバーで記録されるので、result.json は label ごとに 1 つ（lobby と duel）でき、それぞれ 1 件になる。
 */
@ExtendWith(LobbyArena::class)
@ExtendWith(DuelArena::class)
class MultiServerTest {
    @Test
    @DisplayName("runs two distinct servers side by side with their own players, chat and screenshots")
    suspend fun `two servers`(lobby: LobbyArena, duel: DuelArena) {
        val lobbyServer = lobby.server
        val duelServer = duel.server
        val carol = lobby.carol
        val dave = duel.dave
        // 2 台が別のサーバー（別のポート・別の result）であること
        assertNotEquals(lobbyServer.joinAddress.port, duelServer.joinAddress.port, "join ports")
        assertNotEquals(lobbyServer.resultId, duelServer.resultId, "result ids")
        assertTrue(lobbyServer.resultId.endsWith("-lobby"), lobbyServer.resultId)
        assertTrue(duelServer.resultId.endsWith("-duel"), duelServer.resultId)
        // 各プレイヤーは自分の拡張のサーバーに参加している
        assertSame(lobbyServer, carol.server)
        assertSame(duelServer, dave.server)
        assertEquals(listOf("Carol"), lobbyServer.players.map { it.name })
        assertEquals(listOf("Dave"), duelServer.players.map { it.name })

        // 各サーバーの RCON のコマンドは、そのサーバーのプレイヤーにだけ届く
        broadcastStaysOnItsServer(lobbyServer, carol, dave, "fukurou lobby")
        broadcastStaysOnItsServer(duelServer, dave, carol, "fukurou duel")

        // Adventure の Audience として各サーバーのプレイヤーに送り、クライアントログで受け取りを確かめる
        val lobbyMessage = Component.text("hello from the lobby", NamedTextColor.AQUA)
        val duelMessage = Component.text("hello from the duel", NamedTextColor.RED)
        carol.sendMessage(lobbyMessage)
        dave.sendMessage(duelMessage)
        carol.awaitChat(lobbyMessage)
        dave.awaitChat(duelMessage)

        // 各サーバーに足場を置き、プレイヤーをその上へ移す（スクリーンショットが奈落を向かないように）
        prepareStage(lobbyServer, "minecraft:quartz_block")
        prepareStage(duelServer, "minecraft:red_concrete")
        carol.teleport(0.5, -60.0, 0.5, yaw = 0f, pitch = 30f)
        dave.teleport(0.5, -60.0, 0.5, yaw = 0f, pitch = 30f)
        // チャンクの描画を待つ（両方のサーバーのテストに記録される待ち）
        pause(2.seconds)

        // それぞれのサーバーで撮る。ファイルは各 result の出力に分かれる
        val shots = listOf(carol.screenshot("lobby"), dave.screenshot("duel"))
        assertEquals(listOf("Carol", "Dave"), shots.map { it.player })
        shots.forEach { shot ->
            assertTrue(shot.path.exists(), "screenshot file ${shot.path}")
            assertTrue(shot.width > 0 && shot.height > 0, "screenshot size ${shot.width}x${shot.height}")
        }
        assertNotEquals(shots[0].path, shots[1].path, "screenshot paths")
    }

    /**
     * server で say を実行し、listener には届き、他のサーバーの outsider には届かないことを確かめる。
     *
     * listener が受け取った後なら、同じ放送が outsider にも届いていれば既にログにあるはずなので、その時点で無いことを確かめる。
     */
    private suspend fun broadcastStaysOnItsServer(server: GameServer, listener: Player, outsider: Player, text: String) {
        val pattern = Regex(Regex.escape(text))
        val listenerMark = listener.mark()
        val outsiderMark = outsider.mark()
        val serverMark = server.mark()
        server.command("say $text")
        server.awaitLog(pattern, after = serverMark)
        listener.awaitChat(pattern, timeout = 20.seconds, after = listenerMark)
        outsider.assertNoChat(pattern, after = outsiderMark)
    }

    /** 足場を置き、昼・晴れにする（examples/fukurou.yml の platform と同じ考え方）。 */
    private suspend fun prepareStage(server: GameServer, block: String) {
        server.fill(BlockPos(-3, -61, -3), BlockPos(3, -61, 3), block)
        server.time(6000)
        server.weatherClear()
    }
}
