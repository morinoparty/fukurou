package party.morino.fukurou.player

/**
 * マウスのボタン。
 *
 * @property xButton xdotool のボタン番号
 * @property label ステップの label
 */
public enum class MouseButton(public val xButton: Int, public val label: String) {
    /** 左（攻撃・ブロックの破壊・スロットのクリック）。 */
    LEFT(1, "left"),

    /** 中（ピックブロック）。 */
    MIDDLE(2, "middle"),

    /** 右（使う・置く）。 */
    RIGHT(3, "right"),
}
