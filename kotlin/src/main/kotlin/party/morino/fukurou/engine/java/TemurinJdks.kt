package party.morino.fukurou.engine.java

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import party.morino.fukurou.engine.net.CacheLock
import party.morino.fukurou.engine.net.Downloader
import party.morino.fukurou.engine.net.Http
import party.morino.fukurou.error.SetupException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Adoptium の API から Temurin の JDK を取得してキャッシュに展開する（v3 設計 §3）。
 *
 * <jdksDir>/temurin-<major>/ に展開し、その bin/java を返す。展開済みならそのまま使う。
 *
 * @property http API の問い合わせに使う HTTP
 * @property downloader アーカイブのダウンロード（sha256 を確かめる）
 * @property apiUrl Adoptium の API の基点（テストでは手元のサーバー）
 * @property architectureOverride Adoptium の architecture（x64 / aarch64）。null なら os.arch から決める
 */
internal class TemurinJdks(
    private val http: Http = Http(),
    private val downloader: Downloader = Downloader(),
    private val apiUrl: String = ADOPTIUM_API,
    private val architectureOverride: String? = null,
) {
    /** 問い合わせる architecture。ダウンロードが要るときだけ決める（対応していない環境でも current やパスは使える）。 */
    private val architecture: String get() = architectureOverride ?: adoptiumArchitecture()

    /**
     * Adoptium が示すアーカイブ 1 つ。
     *
     * @property releaseName リリースの名前（jdk-21.0.4+7 など）
     * @property name アーカイブのファイル名
     * @property link ダウンロードの URL
     * @property sha256 アーカイブの SHA-256
     */
    data class Package(val releaseName: String, val name: String, val link: String, val sha256: String)

    /** major の JDK の展開先。 */
    fun home(jdksDir: Path, major: Int): Path = jdksDir.resolve("temurin-$major")

    /**
     * major の Temurin の java を返す。無ければダウンロードして展開する。
     * 同じキャッシュを使う他のテストや JVM と重ならないよう、<jdksDir>/.temurin-<major> のロックの下で行う。
     */
    suspend fun ensure(major: Int, jdksDir: Path, log: (String) -> Unit): Path {
        val home = home(jdksDir, major)
        val java = home.resolve("bin").resolve("java")
        return CacheLock.withLock(jdksDir.resolve(".temurin-$major")) {
            // 展開済みなら何もしない（置き換えは原子的なので、bin/java があれば完全な展開）
            if (Files.isRegularFile(java)) return@withLock java
            val pkg = latest(major)
            log("downloading Temurin ${pkg.releaseName} (${pkg.name}) for the server")
            val archive = downloader.download(pkg.link, jdksDir.resolve(pkg.name), pkg.sha256)
            runInterruptible(Dispatchers.IO) { install(archive, home) }
            // 展開を終えたアーカイブは要らない（大きいので残さない）
            Files.deleteIfExists(archive)
            log("installed Temurin ${pkg.releaseName} at $home")
            java
        }
    }

    /** major の最新の JDK（linux、hotspot、eclipse）のアーカイブを API に問い合わせる。 */
    suspend fun latest(major: Int): Package {
        val url = "$apiUrl/v3/assets/latest/$major/hotspot?architecture=$architecture&image_type=jdk&os=linux&vendor=eclipse"
        val assets = http.getJson(url) as? JsonArray
            ?: throw SetupException("unexpected response from $url: not a JSON array")
        val asset = assets.firstOrNull() as? JsonObject
            ?: throw SetupException("Adoptium has no Temurin $major JDK for linux $architecture ($url)")
        val pkg = (asset["binary"] as? JsonObject)?.get("package") as? JsonObject
            ?: throw SetupException("unexpected response from $url: no binary.package")
        fun field(source: JsonObject, name: String): String =
            (source[name] as? JsonPrimitive)?.contentOrNull
                ?: throw SetupException("unexpected response from $url: no binary.package.$name")
        val releaseName = (asset["release_name"] as? JsonPrimitive)?.contentOrNull ?: "Temurin $major"
        val name = field(pkg, "name")
        // アーカイブの名前はキャッシュのパスになるので、区切りを含むものは受け付けない
        if ('/' in name || name.startsWith(".")) throw SetupException("unexpected archive name from $url: $name")
        return Package(releaseName, name, field(pkg, "link"), field(pkg, "checksum"))
    }

    /** アーカイブを home の隣の一時ディレクトリに展開し、確かめてから home へ原子的に移す。 */
    private fun install(archive: Path, home: Path) {
        val partial = home.resolveSibling("${home.fileName}.part")
        deleteRecursively(partial)
        try {
            // アーカイブは jdk-21.0.4+7/ の 1 階層の下に入っているので、それを外す
            Files.newInputStream(archive).use { TarExtractor.extractTarGz(it, partial, stripComponents = 1) }
            if (!Files.isRegularFile(partial.resolve("bin").resolve("java"))) {
                throw SetupException("the JDK archive $archive has no bin/java")
            }
            // 壊れた（bin/java の無い）前回の展開が残っていれば置き換える
            deleteRecursively(home)
            Files.move(partial, home, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: java.io.IOException) {
            // 壊れたアーカイブやディスクの不足も、原因の分かる準備の失敗として伝える
            throw SetupException("could not extract the JDK archive $archive into $home: $error", error)
        } finally {
            deleteRecursively(partial)
        }
    }

    companion object {
        /** Adoptium の API。 */
        const val ADOPTIUM_API: String = "https://api.adoptium.net"

        /** この JVM の os.arch を Adoptium の architecture にする。対応していなければ SetupException。 */
        fun adoptiumArchitecture(osArch: String = System.getProperty("os.arch").orEmpty()): String =
            when (osArch.lowercase(Locale.ROOT)) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw SetupException(
                    "fukurou.serverJava=auto cannot download a JDK for the $osArch architecture; " +
                        "set -Pfukurou.serverJava=current or the path of a java executable",
                )
            }

        /** ディレクトリを中身ごと消す。リンクはたどらない。 */
        fun deleteRecursively(path: Path) {
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
