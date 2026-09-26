package party.morino.fukurou.world

import net.kyori.adventure.key.Key
import java.math.BigDecimal

/**
 * ワールド内の位置。yaw と pitch は両方指定するか両方省略する（tp の引数の形）。
 *
 * @property x X 座標
 * @property y Y 座標
 * @property z Z 座標
 * @property yaw 水平の向き（度）
 * @property pitch 垂直の向き（度）
 * @property world 次元のキー
 */
public data class Location(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float? = null,
    val pitch: Float? = null,
    val world: Key = Worlds.OVERWORLD,
) {
    init {
        // tp は yaw だけ・pitch だけを受け付けないので、片方だけの指定は作れないようにする
        require((yaw == null) == (pitch == null)) { "yaw and pitch must be given together" }
    }

    /** 純粋: "0.5 -49 0.5 0 30"。Python の :g と同じく末尾の 0 を落とす。 */
    public fun toCommandArgs(): String {
        val coordinates = listOf(x, y, z).map { format(BigDecimal.valueOf(it)) }
        // Float は toString 経由で BigDecimal にし、0.1f が 0.10000000149… にならないようにする
        val rotation = listOfNotNull(yaw, pitch).map { format(BigDecimal(it.toString())) }
        return (coordinates + rotation).joinToString(" ")
    }

    /** 末尾の 0 と小数点を落とし、指数表記にしない（10.0 → "10"、1e10 → "10000000000"）。 */
    private fun format(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
}
