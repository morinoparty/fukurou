package party.morino.fukurou.result

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import party.morino.fukurou.result.model.run.ResultV2
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories

/**
 * result.json をアトミックに書き出す（result/recorder.py:375-387 write_result）。
 *
 * 途中で中断されても壊れたファイルが残らないよう、同じディレクトリの一時ファイルに書いてから置き換える。
 *
 * @property file result.json の置き場
 */
internal class ResultWriter(val file: Path) {
    /**
     * ResultV2 を camelCase の JSON として書く。
     *
     * @throws java.io.IOException 書き込みや置き換えに失敗した
     */
    @Synchronized
    fun write(result: ResultV2) {
        file.parent?.createDirectories()
        // 一時ファイルは同じディレクトリに置く（別のファイルシステムだと ATOMIC_MOVE できない）
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        val bytes = encode(result).toByteArray(Charsets.UTF_8)
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            // write は一度で書き切るとは限らないので、残りが無くなるまで繰り返す
            while (buffer.hasRemaining()) channel.write(buffer)
            // 置き換えた後に電源断やカーネルの停止があっても中身が残るよう、先にディスクへ書き出す
            channel.force(true)
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** JSON の設定。 */
    companion object {
        /** §6.3 の設定。null も既定値も省かず、Python（indent=2）と同じ字下げで書く。 */
        @OptIn(ExperimentalSerializationApi::class)
        val JSON: Json = Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** result.json の本文（末尾に改行を付ける）。 */
        fun encode(result: ResultV2): String = JSON.encodeToString(ResultV2.serializer(), result) + "\n"
    }
}
