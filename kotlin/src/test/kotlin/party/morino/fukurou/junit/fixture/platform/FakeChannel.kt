package party.morino.fukurou.junit.fixture.platform

import party.morino.fukurou.server.CommandResponse
import party.morino.fukurou.spi.CommandChannel

/** コマンドを記録し、"fail" で始まるものにだけエラーの応答を返す経路。 */
class FakeChannel : CommandChannel {
    override val repliesToCommands: Boolean = true

    override suspend fun send(command: String): CommandResponse {
        FakeEnvironment.commands += command
        return CommandResponse(command, if (command.startsWith("fail")) "Unknown or incomplete command" else "ok")
    }

    override fun close() {}
}
