package party.morino.fukurou.world

/**
 * ブロックの座標。
 *
 * @property x X 座標
 * @property y Y 座標
 * @property z Z 座標
 */
public data class BlockPos(val x: Int, val y: Int, val z: Int) {
    /** コマンドの引数の形 "x y z"。 */
    public fun toCommandArgs(): String = "$x $y $z"
}
