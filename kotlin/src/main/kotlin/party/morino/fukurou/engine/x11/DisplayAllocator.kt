package party.morino.fukurou.engine.x11

import java.nio.file.Files
import java.nio.file.Path

/**
 * Xvfb のディスプレイ番号を JVM の中で重ならないように割り当てる（runner/xvfb.py:72-77）。
 *
 * xvfb-run -a と同様に 99 から順に探し、ロックファイル（/tmp/.X<n>-lock）とソケット（/tmp/.X11-unix/X<n>）が
 * ある番号と、この JVM が既に渡した番号を飛ばす。同時に起動する別のレーンが同じ番号を取らないようにするため。
 *
 * @property root ファイルを探す場所（通常は /tmp。テストでは一時ディレクトリを渡す）
 */
internal class DisplayAllocator(private val root: Path = Path.of("/tmp")) {
    /** 渡してまだ返されていない番号。 */
    private val handedOut = HashSet<Int>()

    /** start 以上で使われていない番号を割り当てる。 */
    @Synchronized
    fun allocate(start: Int = FIRST_DISPLAY_NUMBER): Int {
        var number = start
        // 別の X サーバーが使っている番号と、この JVM が渡した番号を飛ばす
        while (number in handedOut || inUse(number)) number++
        handedOut.add(number)
        return number
    }

    /** Xvfb を止めた、または起動できなかった番号を返す。 */
    @Synchronized
    fun release(number: Int) {
        handedOut.remove(number)
    }

    /** number のロックファイルかソケットがあるか。 */
    private fun inUse(number: Int): Boolean =
        Files.exists(root.resolve(".X$number-lock")) || Files.exists(root.resolve(".X11-unix").resolve("X$number"))

    companion object {
        /** この番号から順に空いている番号を探す（xvfb-run -a と同じ）。 */
        const val FIRST_DISPLAY_NUMBER: Int = 99

        /** JVM で共有する割り当て。 */
        val SHARED: DisplayAllocator = DisplayAllocator()
    }
}
