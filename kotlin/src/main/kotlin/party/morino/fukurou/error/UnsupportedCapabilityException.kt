package party.morino.fukurou.error

/**
 * サーバーの種類がその操作（能力）を提供していない。
 *
 * Adventure の Audience は「未対応なら何もしない」が契約だが、fukurou は黙って無視すると
 * タイトルの写っていないスクリーンショットで緑になるため、例外にする。
 *
 * @property typeId サーバーの種類の id（"paper" など）
 * @property capability 足りない能力やメソッドの名前
 */
public class UnsupportedCapabilityException(
    public val typeId: String,
    public val capability: String,
) : UnsupportedOperationException("$typeId does not support $capability")
