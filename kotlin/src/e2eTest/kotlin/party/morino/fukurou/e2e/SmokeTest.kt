package party.morino.fukurou.e2e

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.fukurou.server.GameServer

/**
 * 本物の Paper とクライアントで、コマンド・スクリーンショット・ログの待ち・Adventure の送信を一通り通す。
 * Xvfb などが要るので CI の kotlin-e2e ジョブだけで実行する（check には含めない）。
 */
@ExtendWith(SmokeArena::class)
class SmokeTest {
    @Test
    @DisplayName("sends a command, waits for the log, shows a title and takes a screenshot")
    suspend fun `smoke`(arena: SmokeArena, server: GameServer) {
        val alice = arena.alice
        // サーバーのコマンドとログの待ち
        val mark = server.mark()
        server.command("say fukurou smoke")
        server.awaitLog(Regex("""fukurou smoke"""), after = mark)
        // Adventure の Audience としてプレイヤーに送る
        alice.showTitle(Title.title(Component.text("fukurou"), Component.text("smoke")))
        alice.sendMessage(Component.text("hello from fukurou"))
        alice.awaitChat(Component.text("hello from fukurou"))
        alice.screenshot("title")
    }
}
