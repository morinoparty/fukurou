package party.morino.fukurou.spi

import party.morino.fukurou.server.CommandResponse
import party.morino.fukurou.spi.model.CommandCall

/** コマンドを 1 つ送り、応答（あれば）を返す。応答の無い経路は text=null。スレッドセーフであること。 */
@FukurouSpi
public interface CommandChannel : AutoCloseable {
    /** コマンドを送る（先頭の "/" は付けない）。サーバーに届かなければ ServerUnavailableException。 */
    public suspend fun send(command: String): CommandResponse

    /** 応答を返す経路か（RCON は true、標準入力のコンソールは false）。 */
    public val repliesToCommands: Boolean
}

/** 応答のエラー判定。null の応答（応答の無い経路）をエラーにしてはいけない。 */
@FukurouSpi
public fun interface ResponseCheck {
    /** call への response がエラーか。call.ignore の断片を含む応答はエラーにしない。 */
    public fun isError(call: CommandCall, response: String?): Boolean
}
