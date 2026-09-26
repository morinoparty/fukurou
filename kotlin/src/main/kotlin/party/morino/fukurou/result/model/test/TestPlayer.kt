package party.morino.fukurou.result.model.test

import kotlinx.serialization.Serializable

/**
 * そのテストの参加プレイヤー。リセット時に op / deop をこの宣言に合わせる。
 *
 * @property name プレイヤー名
 * @property op op にするか
 */
@Serializable
public data class TestPlayer(val name: String, val op: Boolean = false)
