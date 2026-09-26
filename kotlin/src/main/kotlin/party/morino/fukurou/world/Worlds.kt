package party.morino.fukurou.world

import net.kyori.adventure.key.Key

/** 次元のキー。Adventure の Key をそのまま使う。 */
public object Worlds {
    /** オーバーワールド。 */
    public val OVERWORLD: Key = Key.key("minecraft", "overworld")

    /** ネザー。 */
    public val NETHER: Key = Key.key("minecraft", "the_nether")

    /** ジ・エンド。 */
    public val END: Key = Key.key("minecraft", "the_end")
}
