package party.morino.fukurou.result.model.session

import kotlinx.serialization.Serializable

/**
 * 回収したログファイル。
 *
 * @property kind 種類
 * @property path run ディレクトリからの相対パス
 * @property player クライアントのログならそのプレイヤー
 */
@Serializable
public data class LogInfo(val kind: LogKind, val path: String, val player: String? = null)
