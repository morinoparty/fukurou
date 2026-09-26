package party.morino.fukurou.engine.paper

import party.morino.fukurou.error.SetupException
import party.morino.fukurou.spi.capability.PluginSupport
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** 実行ごとにまっさらなサーバーディレクトリを用意する（server/server_dir.py:29-70）。 */
internal object PaperDirectory {
    /** 設定ファイルの名前。 */
    private const val PROPERTIES_FILE = "server.properties"

    /**
     * server-files を先にコピーし、その後にプラグインと fukurou の設定を書く。
     * server-files に server.properties があっても、その値は上書き指定の 1 つとして合成し、ポートや RCON は fukurou の値を使う。
     * 前回のワールドや設定が影響しないよう、既存のディレクトリは消して作り直す。
     *
     * @return 無視した管理キー（呼び出し側が警告する）
     */
    fun prepare(
        serverDir: Path,
        serverFiles: Path?,
        plugins: List<ResolvedPlugin>,
        pluginSupport: PluginSupport,
        properties: Map<String, String>,
        managed: Map<String, String>,
    ): List<String> {
        deleteRecursively(serverDir)
        Files.createDirectories(serverDir)
        serverFiles?.let { copyServerFiles(it, serverDir) }
        pluginSupport.install(serverDir, plugins)
        val ignored = writeProperties(serverDir, properties, managed)
        // acceptEula は Fukurou が起動前に確かめているので、ここでは常に同意を書く
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true\n")
        return ignored
    }

    /** 既定値 < server-files の server.properties < 種類の properties < 管理キー の順に合成して書く。 */
    private fun writeProperties(serverDir: Path, properties: Map<String, String>, managed: Map<String, String>): List<String> {
        val path = serverDir.resolve(PROPERTIES_FILE)
        val layers = buildList {
            if (Files.isRegularFile(path)) {
                try {
                    add(ServerProperties.parse(Files.readString(path)))
                } catch (error: IllegalArgumentException) {
                    throw SetupException("invalid server properties in the server files: ${error.message}", error)
                }
            }
            add(properties)
        }
        val merged = ServerProperties.merge(layers, managed)
        Files.writeString(path, ServerProperties.render(merged.properties))
        return merged.ignored
    }

    /** server-files のディレクトリの中身をサーバーディレクトリへ重ねる。 */
    private fun copyServerFiles(source: Path, serverDir: Path) {
        if (!Files.isDirectory(source)) throw SetupException("server files directory $source does not exist")
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(serverDir.resolve(source.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    // 同じ名前があれば上書きする（shutil.copytree の dirs_exist_ok=True と同じ）
                    Files.copy(file, serverDir.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** ディレクトリを中身ごと消す。無ければ何もしない（シンボリックリンクはたどらない）。 */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
