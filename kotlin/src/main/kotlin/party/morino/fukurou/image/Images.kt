package party.morino.fukurou.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import party.morino.fukurou.engine.step.StepRunner
import party.morino.fukurou.engine.step.StepScope
import party.morino.fukurou.engine.test.ActiveTests
import party.morino.fukurou.player.Screenshot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * 画像の中の長方形（ピクセル）。
 *
 * @property x 左端
 * @property y 上端
 */
public data class Region(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "region must not be empty: ${width}x$height" }
        require(x >= 0 && y >= 0) { "region must start inside the image: ($x, $y)" }
    }

    /** (px, py) がこの中か。 */
    public operator fun contains(point: Pair<Int, Int>): Boolean =
        point.first in x until x + width && point.second in y until y + height
}

/** 色（各 0〜255）。 */
public data class Rgb(val red: Int, val green: Int, val blue: Int) {
    /** 各チャンネルの差の最大。 */
    public fun distanceTo(other: Rgb): Int =
        maxOf(kotlin.math.abs(red - other.red), kotlin.math.abs(green - other.green), kotlin.math.abs(blue - other.blue))

    public companion object {
        /** BufferedImage.getRGB の値から作る（アルファは捨てる）。 */
        public fun of(argb: Int): Rgb = Rgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

/**
 * 2 枚の画像の差。
 *
 * @property differentPixels 許容差を超えたピクセルの数（無視した領域は数えない）
 * @property totalPixels 比べたピクセルの数（無視した領域を除く）
 * @property diffImage 違うピクセルを赤、無視した領域を灰色で塗った画像
 */
public data class ImageDiff(val differentPixels: Int, val totalPixels: Int, val diffImage: BufferedImage) {
    /** 違うピクセルの割合（0.0〜1.0）。 */
    public val ratio: Double get() = if (totalPixels == 0) 0.0 else differentPixels.toDouble() / totalPixels
}

/** 画像の比較（純粋）。 */
public object ImageComparison {
    /** 違うピクセルの色（赤）。 */
    private const val DIFFERENT: Int = 0xFF0000

    /** 無視した領域の色（灰色）。 */
    private const val IGNORED: Int = 0x808080

    /** 同じピクセルを暗くする割合（元の色の 1/DIM）。 */
    private const val DIM: Int = 3

    /**
     * actual と expected を比べる。大きさが違えば全ピクセルが違うとする。
     *
     * 各チャンネルの差の最大（Rgb.distanceTo）が channelTolerance を超えたピクセルを違うとする。
     * ignore の領域（画像の外にはみ出した分は切り捨てる）は数えない。差の画像は actual の大きさで、
     * 違うピクセルを赤、無視した領域を灰色、それ以外を actual を暗くした色で塗る。
     *
     * @throws IllegalArgumentException channelTolerance が 0〜255 の外
     */
    public fun compare(
        actual: BufferedImage,
        expected: BufferedImage,
        channelTolerance: Int = 16,
        ignore: List<Region> = emptyList(),
    ): ImageDiff {
        require(channelTolerance in 0..255) { "channelTolerance must be in 0..255, got $channelTolerance" }
        val width = actual.width
        val height = actual.height
        val diff = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        if (width != expected.width || height != expected.height) {
            // 大きさが違えば比べようがないので、差は 100%
            for (y in 0 until height) for (x in 0 until width) diff.setRGB(x, y, DIFFERENT)
            val total = width * height
            return ImageDiff(total, total, diff)
        }
        // 無視する領域を先に印にしておく（ピクセルごとに領域の一覧をたどらない）
        val ignored = BooleanArray(width * height)
        for (region in ignore) {
            for (y in region.y until minOf(region.y + region.height, height)) {
                for (x in region.x until minOf(region.x + region.width, width)) ignored[y * width + x] = true
            }
        }
        var different = 0
        var total = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (ignored[y * width + x]) {
                    diff.setRGB(x, y, IGNORED)
                    continue
                }
                total++
                val pixel = Rgb.of(actual.getRGB(x, y))
                if (pixel.distanceTo(Rgb.of(expected.getRGB(x, y))) > channelTolerance) {
                    different++
                    diff.setRGB(x, y, DIFFERENT)
                } else {
                    diff.setRGB(x, y, (pixel.red / DIM shl 16) or (pixel.green / DIM shl 8) or (pixel.blue / DIM))
                }
            }
        }
        return ImageDiff(different, total, diff)
    }
}

/** PNG を読む。 */
public fun Screenshot.image(): BufferedImage = Images.read(path)

/**
 * 1 ピクセルの色。
 *
 * @throws IllegalArgumentException (x, y) が画像の外
 */
public fun Screenshot.pixel(x: Int, y: Int): Rgb {
    val image = image()
    require(x in 0 until image.width && y in 0 until image.height) { "($x, $y) is outside the ${image.width}x${image.height} image" }
    return Rgb.of(image.getRGB(x, y))
}

/**
 * 領域の平均の色（各チャンネルの平均を四捨五入）。
 *
 * @throws IllegalArgumentException 領域が画像からはみ出している
 */
public fun Screenshot.averageColor(region: Region): Rgb = Images.averageColor(image(), region)

/**
 * 基準画像と比べ、違いが maxDifferentRatio を超えたら `<name>.diff.png` を書いて AssertionError（compare_screenshot ステップ）。
 * 基準画像が無いか fukurou.updateBaselines=true なら、このスクリーンショットを基準画像として書いて成功する。
 */
public suspend fun Screenshot.assertMatches(
    baseline: Path,
    maxDifferentRatio: Double = 0.02,
    channelTolerance: Int = 16,
    ignore: List<Region> = emptyList(),
): Unit = Images.assertMatches(this, baseline, maxDifferentRatio, channelTolerance, ignore, Images.updateBaselines())

/** 画像の読み書きと比較のステップ（公開しない部分）。 */
internal object Images {
    /** 基準画像を書き直すかのシステムプロパティ。 */
    const val UPDATE_PROPERTY: String = "fukurou.updateBaselines"

    /** 基準画像を書き直すかの環境変数。 */
    const val UPDATE_ENV: String = "FUKUROU_UPDATE_BASELINES"

    /** compare_screenshot のアクション名。 */
    const val ACTION: String = "compare_screenshot"

    /** システムプロパティ（無ければ環境変数）が true なら、基準画像を書き直す。 */
    fun updateBaselines(
        property: String? = System.getProperty(UPDATE_PROPERTY),
        env: String? = System.getenv(UPDATE_ENV),
    ): Boolean = (property ?: env)?.trim().equals("true", ignoreCase = true)

    /** PNG を読む。 */
    fun read(path: Path): BufferedImage =
        ImageIO.read(path.toFile()) ?: throw IllegalArgumentException("$path is not an image ImageIO can read")

    /** PNG を書く（親のディレクトリも作る）。 */
    fun write(image: BufferedImage, path: Path) {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        check(ImageIO.write(image, "png", path.toFile())) { "no PNG writer is available" }
    }

    /** 領域の平均の色。 */
    fun averageColor(image: BufferedImage, region: Region): Rgb {
        require(region.x + region.width <= image.width && region.y + region.height <= image.height) {
            "$region does not fit in the ${image.width}x${image.height} image"
        }
        var red = 0L
        var green = 0L
        var blue = 0L
        for (y in region.y until region.y + region.height) {
            for (x in region.x until region.x + region.width) {
                val pixel = Rgb.of(image.getRGB(x, y))
                red += pixel.red
                green += pixel.green
                blue += pixel.blue
            }
        }
        val count = region.width.toLong() * region.height
        return Rgb(
            (red.toDouble() / count).roundToInt(),
            (green.toDouble() / count).roundToInt(),
            (blue.toDouble() / count).roundToInt(),
        )
    }

    /** 比べた結果の差の画像の置き場所（スクリーンショットの隣の `<name>.diff.png`）。 */
    fun diffPath(screenshot: Screenshot): Path = screenshot.path.resolveSibling("${screenshot.name}.diff.png")

    /**
     * Screenshot.assertMatches の本体。update が true なら基準画像を書き直す。
     */
    suspend fun assertMatches(
        screenshot: Screenshot,
        baseline: Path,
        maxDifferentRatio: Double,
        channelTolerance: Int,
        ignore: List<Region>,
        update: Boolean,
    ) {
        require(maxDifferentRatio in 0.0..1.0) { "maxDifferentRatio must be in 0.0..1.0, got $maxDifferentRatio" }
        require(channelTolerance in 0..255) { "channelTolerance must be in 0..255, got $channelTolerance" }
        val label = baseline.fileName?.toString() ?: baseline.toString()
        // 撮影したサーバーのテストに記録する（2 台目のサーバーのスクリーンショットを 1 台目のテストに載せない）
        StepRunner.step(screenshot.origin, screenshot.player, ACTION, label) { run, _ ->
            val missing = runInterruptible(Dispatchers.IO) { !Files.exists(baseline) }
            if (missing || update) {
                runInterruptible(Dispatchers.IO) {
                    baseline.toAbsolutePath().parent?.let(Files::createDirectories)
                    Files.copy(screenshot.path, baseline, StandardCopyOption.REPLACE_EXISTING)
                }
                val message = if (missing) {
                    "baseline $baseline did not exist; saved ${screenshot.artifactPath} as the new baseline"
                } else {
                    "$UPDATE_PROPERTY is set; saved ${screenshot.artifactPath} as the baseline $baseline"
                }
                // 記録先が無ければ（テストの外）ハーネスのログも無いので標準エラーへ
                val host = run?.host ?: screenshot.origin ?: StepScope.current()?.run?.host ?: ActiveTests.all().firstOrNull()?.host
                when {
                    host == null -> System.err.println("fukurou: $message")
                    missing -> host.warn(message)
                    else -> host.log(message)
                }
                return@step
            }
            val diff = runInterruptible(Dispatchers.IO) { ImageComparison.compare(read(screenshot.path), read(baseline), channelTolerance, ignore) }
            if (diff.ratio > maxDifferentRatio) {
                val diffFile = diffPath(screenshot)
                runInterruptible(Dispatchers.IO) { write(diff.diffImage, diffFile) }
                throw AssertionError(
                    "screenshot ${screenshot.player}/${screenshot.name} differs from $label: " +
                        "${diff.differentPixels}/${diff.totalPixels} pixels (${percent(diff.ratio)}) > ${percent(maxDifferentRatio)}; " +
                        "diff written to ${diffFile.fileName}",
                )
            }
        }
    }

    /** 割合を百分率の文字列にする。 */
    private fun percent(ratio: Double): String = "%.2f%%".format(Locale.ROOT, ratio * 100)
}
