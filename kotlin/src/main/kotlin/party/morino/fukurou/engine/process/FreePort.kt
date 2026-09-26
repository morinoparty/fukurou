package party.morino.fukurou.engine.process

import java.net.InetAddress
import java.net.ServerSocket

/** 127.0.0.1 の空きポートを OS に選ばせる（runner/process.py:33）。 */
internal object FreePort {
    /** ポート 0 で bind して閉じ、割り当てられた番号を返す。 */
    fun next(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
}
