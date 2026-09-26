package party.morino.fukurou.engine.net

import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/** CacheLockTest が別の JVM で動かす、ロックを一定時間持つだけのプログラム。 */
object CacheLockHolder {
    /** args: ロックするパス、持つミリ秒。ロックを取ったら "locked" を出す。 */
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        CacheLock.withLock(Path.of(args[0])) {
            println("locked")
            System.out.flush()
            Thread.sleep(args[1].toLong())
        }
    }
}
