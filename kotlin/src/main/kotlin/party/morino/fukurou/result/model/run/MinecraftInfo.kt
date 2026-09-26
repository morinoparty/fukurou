package party.morino.fukurou.result.model.run

import kotlinx.serialization.Serializable

/**
 * テストしたサーバー。server は種類の id（Python のモデルは Literal["paper"] だが、種類を増やせるよう文字列にする）。
 *
 * @property version Minecraft のバージョン
 * @property server サーバーの種類
 * @property build Paper のビルド番号
 * @property channel ビルドのチャンネル（小文字）
 */
@Serializable
public data class MinecraftInfo(
    val version: String,
    val server: String = "paper",
    val build: Int? = null,
    val channel: String? = null,
)
