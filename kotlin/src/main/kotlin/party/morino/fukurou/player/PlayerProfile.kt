package party.morino.fukurou.player

/**
 * 参加前のプレイヤー。操作は持たず、GameServer.join で Player になる。
 *
 * 名前は ^[A-Za-z0-9_]{3,16}$ で、"server" は予約（scenario/model.py と同じ）。
 *
 * @property name プレイヤー名（オフラインモードのユーザー名）
 * @property op リセットのたびに op にするか
 */
public data class PlayerProfile(val name: String, val op: Boolean = false) {
    init {
        // Minecraft のユーザー名の規則。ファイル名やコマンドにそのまま使うので記号は入れさせない
        require(NAME.matches(name)) { "player name '$name' must match ${NAME.pattern}" }
        // on: "server" と区別できなくなるため予約語にしている
        require(name != SERVER_TARGET) { "'$SERVER_TARGET' is reserved and cannot be a player name" }
    }

    private companion object {
        /** プレイヤー名の規則。 */
        private val NAME = Regex("^[A-Za-z0-9_]{3,16}$")

        /** ステップの on でサーバーを表す予約語。 */
        private const val SERVER_TARGET = "server"
    }
}
