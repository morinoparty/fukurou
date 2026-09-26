package party.morino.fukurou.player

import java.nio.file.Path

/**
 * 撮影したスクリーンショット。
 *
 * @property player 撮影したプレイヤー
 * @property name スクリーンショット名（ファイル名の stem）
 * @property path 出力ディレクトリ内の実ファイル
 * @property artifactPath run ディレクトリからの相対パス（result.json に書く値）
 * @property width 幅（px）
 * @property height 高さ（px）
 */
public data class Screenshot(
    val player: String,
    val name: String,
    val path: Path,
    val artifactPath: String,
    val width: Int,
    val height: Int,
)
