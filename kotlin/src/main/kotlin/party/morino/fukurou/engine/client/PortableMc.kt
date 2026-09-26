package party.morino.fukurou.engine.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import party.morino.fukurou.engine.net.CacheLock
import party.morino.fukurou.engine.net.Downloader
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** バニラクライアントの起動に使う PortableMC の取得（runner/portablemc.py）。 */
internal object PortableMc {
    /** PortableMC はバージョンとチェックサムを固定し、改ざんされたバイナリを実行しないようにする。 */
    const val VERSION: String = "5.0.4"

    /** リリースのアーカイブ名。 */
    const val ARCHIVE: String = "portablemc-$VERSION-linux-x86_64-gnu.tar.gz"

    /** アーカイブの sha256。 */
    const val SHA256: String = "b14d2dff5191dabf90414562820ffdddfb5ee1acf692729782b4691d55b7b4f8"

    /** ダウンロード元。 */
    const val URL: String = "https://github.com/theorzr/portablemc/releases/download/v$VERSION/$ARCHIVE"

    /** アーカイブ内の実行ファイルの名前。 */
    private const val EXECUTABLE = "portablemc"

    /** toolsDir の下の実行ファイルの置き場所（<toolsDir>/portablemc-5.0.4/portablemc）。 */
    fun executable(toolsDir: Path): Path = toolsDir.resolve("portablemc-$VERSION").resolve(EXECUTABLE)

    /**
     * PortableMC をダウンロード・チェックサム検証・展開し、実行ファイルのパスを返す。
     * 同じ toolsDir を使う他のテストや JVM と重ならないよう、<toolsDir>/.portablemc-5.0.4 のロックの下で行う。
     */
    suspend fun ensure(toolsDir: Path, downloader: Downloader = Downloader()): Path {
        val executable = executable(toolsDir)
        return CacheLock.withLock(toolsDir.resolve(".portablemc-$VERSION")) {
            // 展開済みなら何もしない（置き換えは原子的なので、あれば完全なファイル）
            if (Files.isRegularFile(executable)) return@withLock executable
            // download はチェックサムが一致しなければ保存せずに失敗し、一致する既存のアーカイブはそのまま使う
            val archive = downloader.download(URL, toolsDir.resolve(ARCHIVE), SHA256)
            runInterruptible(Dispatchers.IO) { install(archive, executable) }
            executable
        }
    }

    /** アーカイブから実行ファイルだけを取り出し、755 にして原子的に置く。 */
    private fun install(archive: Path, executable: Path) {
        // アーカイブ内のディレクトリ構成に依存しないよう、実行ファイルだけを取り出す
        val bytes = Files.newInputStream(archive).use { TarGzReader.extract(it, EXECUTABLE) }
            ?: throw SetupException("portablemc executable was not found in the archive $archive")
        Files.createDirectories(executable.parent)
        val partial = executable.resolveSibling("$EXECUTABLE.part")
        try {
            Files.write(partial, bytes)
            Files.setPosixFilePermissions(partial, PosixFilePermissions.fromString("rwxr-xr-x"))
            // 途中まで書いたファイルを実行しないよう、書き終えてから置き換える
            Files.move(partial, executable, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
