package party.morino.fukurou.spi.model

/**
 * 計画されたコマンド。ignore は応答にこの断片があればエラー扱いしない（isolation.py:37 ResetCommand と同じ）。
 *
 * @property command コマンド（先頭の "/" は付けない）
 * @property ignore エラーとみなさない応答の断片
 * @property player コマンドの対象のプレイヤー（"No player was found" でそのプレイヤーを dirty にする）
 * @property route どのサーバーで実行するか
 */
public data class CommandCall(
    val command: String,
    val ignore: List<String> = emptyList(),
    val player: String? = null,
    val route: CommandRoute = CommandRoute.Self,
)
