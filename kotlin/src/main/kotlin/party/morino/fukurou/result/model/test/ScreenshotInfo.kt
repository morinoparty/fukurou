package party.morino.fukurou.result.model.test

import kotlinx.serialization.Serializable

/**
 * テストで撮ったスクリーンショット。
 *
 * @property player 撮ったプレイヤー
 * @property name スクリーンショット名
 * @property path run ディレクトリからの相対パス（tests/<id>/screenshots/<player>/<name>.png）
 * @property width 幅
 * @property height 高さ
 * @property stepIndex 撮ったステップの steps の添字
 */
@Serializable
public data class ScreenshotInfo(
    val player: String,
    val name: String,
    val path: String,
    val width: Int,
    val height: Int,
    val stepIndex: Int? = null,
)
