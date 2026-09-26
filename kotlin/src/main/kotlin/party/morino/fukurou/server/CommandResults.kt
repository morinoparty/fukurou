package party.morino.fukurou.server

/** コマンドの応答をどう扱うか。 */
public enum class CommandCheck {
    /** 種類の ResponseCheck がエラーと判定したら CommandFailedError（既定）。 */
    FailOnError,

    /** 判定せずに応答をそのまま返す（エラーの応答を期待するテスト用）。 */
    ReturnRaw,
}

/**
 * コマンドの応答。
 *
 * @property command 送ったコマンド（先頭の "/" は外したもの）
 * @property text 応答。応答を返さない経路（標準入力のコンソールなど）では null
 */
public data class CommandResponse(val command: String, val text: String?) {
    /** 応答の本文。応答を返さない経路では IllegalStateException。 */
    public fun requireText(): String =
        text ?: throw IllegalStateException("\"$command\" returned no response; this server's command channel does not reply")
}

/**
 * 応答が種類の ResponseCheck でエラーと判定された（プラグインやテストの誤り → テストは failed）。
 *
 * @property command 送ったコマンド
 * @property response サーバーの応答
 */
public class CommandFailedError(
    public val command: String,
    public val response: String?,
    serverLabel: String,
) : AssertionError(
        // §1.10 の書式。応答を期待しているなら ReturnRaw を使うよう案内する
        "Command failed on $serverLabel: \"$command\"\n" +
            "  → \"${response.orEmpty()}\"\n" +
            "Pass check = CommandCheck.ReturnRaw if this response is expected.",
    )
