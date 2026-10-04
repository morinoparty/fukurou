package party.morino.fukurou.player

import party.morino.fukurou.engine.test.StepHost
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
) {
    /**
     * 撮影したサーバー。比べるステップ（compare_screenshot）をそのサーバーのテストに記録するのに使う。
     * 1 つのテストを 2 台のサーバーで実行していても、別のサーバーのテストに載せない。
     * fukurou の外で作ったもの（と copy の結果）は null で、そのときは記録先を StepScope と実行中のテストから決める。
     */
    internal var origin: StepHost? = null
}
