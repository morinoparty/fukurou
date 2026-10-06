package party.morino.fukurou.engine.java

import party.morino.fukurou.Fukurou
import party.morino.fukurou.ServerJava
import party.morino.fukurou.engine.plugin.JavaRequirement
import party.morino.fukurou.error.SetupException
import party.morino.fukurou.spi.plugin.ResolvedPlugin
import party.morino.fukurou.version.MinecraftVersion
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** fukurou.serverJava を実際の java の実行ファイルに解決する（v3 設計 §3）。 */
internal object ServerJavaResolver {
    /** テストの JVM の java。プロセスのコマンドが分からなければ java.home の bin/java。 */
    fun currentJava(): Path =
        ProcessHandle.current().info().command().orElse(null)?.let { Path.of(it) }
            ?: Path.of(System.getProperty("java.home"), "bin", "java")

    /**
     * fukurou.serverJava に書かれた実行ファイルを実際のパスにする。区切りを含まない名前（java）は PATH から探す。
     *
     * @throws SetupException 名前が PATH に見つからない
     */
    fun executable(configured: Path, pathEnv: String = System.getenv("PATH").orEmpty()): Path {
        if (configured.isAbsolute || configured.nameCount != 1) return configured
        return findOnPath(configured.toString(), pathEnv)
            ?: throw SetupException("Java executable $configured (fukurou.serverJava) was not found on PATH")
    }

    /** PATH の順に探し、最初の実行できる通常ファイルを返す（シェルと同じ）。空の要素は cwd。 */
    fun findOnPath(name: String, pathEnv: String): Path? =
        pathEnv.split(File.pathSeparatorChar)
            .asSequence()
            .map { dir -> Path.of(dir.ifEmpty { "." }).resolve(name) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?.toAbsolutePath()

    /**
     * fukurou.serverJava に従って java を決める。
     *
     * auto は 必要な major = max(Mojang の要求, プラグインの class major - 44) の JDK を使う（[auto]）。
     * current とパスは v2 と同じで、足りるかどうかはこの後の JavaCheck が確かめる。
     */
    suspend fun resolve(
        fukurou: Fukurou,
        version: MinecraftVersion,
        plugins: List<ResolvedPlugin>,
        log: (String) -> Unit,
        warn: (String) -> Unit,
    ): Path = when (val choice = fukurou.config.serverJava) {
        is ServerJava.Path -> executable(choice.executable)
        ServerJava.Current -> currentJava()
        ServerJava.Auto -> {
            val required = JavaRequirement.required(fukurou.mojang.javaMajor(version.id), plugins)
            auto(
                required = required,
                currentFeature = Runtime.version().feature(),
                currentJava = currentJava(),
                env = System.getenv(),
                cacheDir = fukurou.config.workDir.resolve("cache"),
                jdks = TemurinJdks(downloader = fukurou.downloader),
                log = log,
            )
        }
    }

    /**
     * 必要な major の java を選ぶ。純粋ではないが、環境と取得先を引数で差し替えられる。
     *
     * 1. テストの JVM の major が required と同じならそれ
     * 2. GitHub のランナーの JAVA_HOME_<required>_X64（arm なら _ARM64）に bin/java があればそれ
     * 3. <cacheDir>/jdks/temurin-<required>/ の Temurin（無ければ Adoptium からダウンロードして展開）
     *
     * @param required 必要な Java の major
     * @param currentFeature テストの JVM の major
     * @param currentJava テストの JVM の java
     * @param env 環境変数
     * @param cacheDir 共有キャッシュ（<workDir>/cache）
     * @param jdks Temurin の取得
     * @param log harness.log への記録
     * @param osArch os.arch（JAVA_HOME_* の接尾辞を決める）
     */
    suspend fun auto(
        required: Int,
        currentFeature: Int,
        currentJava: Path,
        env: Map<String, String>,
        cacheDir: Path,
        jdks: TemurinJdks,
        log: (String) -> Unit,
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): Path {
        if (currentFeature == required) {
            log("server Java $required: using the test JVM ($currentJava)")
            return currentJava
        }
        val homeVariable = "JAVA_HOME_${required}_${runnerSuffix(osArch)}"
        val runnerJava = env[homeVariable]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it, "bin", "java") }
        if (runnerJava != null && Files.isRegularFile(runnerJava)) {
            log("server Java $required: using $runnerJava from $homeVariable (the test JVM is Java $currentFeature)")
            return runnerJava
        }
        val java = jdks.ensure(required, cacheDir.resolve("jdks"), log)
        // 実際に起動して版を確かめる（壊れた展開や別の版を黙って使わない）
        val actual = JavaRequirement.actualMajor(java)
        if (actual != required) {
            throw SetupException(
                "the cached JDK $java reports Java ${actual ?: "of an unknown version"}, not $required; " +
                    "delete ${java.parent.parent} to download it again",
            )
        }
        log("server Java $required: using Temurin at $java (the test JVM is Java $currentFeature)")
        return java
    }

    /** GitHub のランナーの JAVA_HOME_<major>_<接尾辞> の接尾辞。 */
    private fun runnerSuffix(osArch: String): String = when (osArch.lowercase(Locale.ROOT)) {
        "aarch64", "arm64" -> "ARM64"
        else -> "X64"
    }
}
