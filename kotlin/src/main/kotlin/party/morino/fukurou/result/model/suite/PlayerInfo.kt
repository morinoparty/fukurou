package party.morino.fukurou.result.model.suite

import kotlinx.serialization.Serializable

/**
 * セッションに参加させるプレイヤー。op はテストごとなので tests[].players にある。
 *
 * @property name プレイヤー名
 * @property joined 参加できたか
 */
@Serializable
public data class PlayerInfo(val name: String, val joined: Boolean = false)
