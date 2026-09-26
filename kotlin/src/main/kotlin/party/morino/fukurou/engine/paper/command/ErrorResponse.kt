package party.morino.fukurou.engine.paper.command

import party.morino.fukurou.spi.ResponseCheck
import party.morino.fukurou.spi.model.CommandCall

/**
 * Paper（バニラ）の RCON の応答のエラー判定（run/isolation.py:21-33,48）。純粋。
 *
 * RCON はコマンドの失敗を例外にせず応答文で返すので、応答の行頭の定型でエラーを見分ける。
 */
internal object ErrorResponse : ResponseCheck {
    /**
     * 応答がこれで始まる行を含めばコマンドは失敗している（isolation.py ERROR_RESPONSE そのまま）。
     * "No player was found" は、プレイヤーが切断されているときの tp / clear などの応答。
     */
    val ERROR_RESPONSE: Regex = Regex(
        "^(Unknown or incomplete command|Incorrect argument|Too many blocks|Cannot|That position is not loaded" +
            "|No player was found)",
        RegexOption.MULTILINE,
    )

    /** 参加プレイヤーがサーバーに居ないときの応答。これを受けたプレイヤーは次のテストの前に起動し直す。 */
    const val PLAYER_MISSING: String = "No player was found"

    override fun isError(call: CommandCall, response: String?): Boolean {
        // 応答の無い経路では判定できないので、エラーにしない
        if (response == null) return false
        // kill で殺すものが無い、op が既に op など、失敗ではない応答は無視する
        if (call.ignore.any { it in response }) return false
        return ERROR_RESPONSE.containsMatchIn(response)
    }
}
