package party.morino.fukurou.cli

import party.morino.fukurou.engine.paper.PaperBuildPicker
import party.morino.fukurou.server.paper.PaperChannel
import java.util.Locale

/**
 * バージョン指定が不正、または条件に合うバージョンが見つからない。終了コードは 2 になる。
 *
 * @param message 利用者にそのまま見せるメッセージ
 */
internal class VersionError(message: String) : RuntimeException(message)

/**
 * テストに使う Minecraft のバージョンを、Mojang のマニフェストと Paper のビルドから選ぶ。
 *
 * 1.21.11 と 26.1 のように番号の体系が変わるため大小は比べず、マニフェストの並び（リリースの新しい順）だけで前後を決める。
 * 取得済みのデータだけを使う純粋な処理で、ビルドの有無だけを [hasBuild] で問い合わせる。
 */
internal object VersionSelection {
    /** fukurou が対応する最も古いバージョン。これより古い指定は誤り。 */
    const val MIN_SUPPORTED: String = "1.20"

    /** 範囲が解決してよいバージョンの数の既定。 */
    const val DEFAULT_MAX_VERSIONS: Int = 16

    /** 最新を表す指定。 */
    const val LATEST: String = "latest"

    /** --paper-channel の既定。 */
    const val DEFAULT_PAPER_CHANNEL: String = "stable"

    /** --paper-channel に指定できる値（許容する最も不安定なチャンネル）。 */
    val PAPER_CHANNELS: List<String> = PaperChannel.entries.map { it.name.lowercase(Locale.ROOT) }

    /** --paper-channel の値を読む。不正なら [VersionError]。 */
    fun parseChannel(text: String): PaperChannel =
        PaperChannel.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == text }
            ?: throw VersionError("unknown Paper channel '$text'; expected one of ${PAPER_CHANNELS.joinToString(", ")}")

    /**
     * バージョン指定を、テストするバージョンのリスト（古い順）にする。
     *
     * - "latest": Paper に許容するビルドがある最新リリース
     * - "1.21.11": そのバージョンのみ（許容するビルドが無ければ誤り）
     * - "1.20.5-": 1.20.5 以降で Paper に許容するビルドがある全リリース
     * - "1.20.5-1.21.11": 両端を含む範囲で Paper に許容するビルドがある全リリース
     *
     * @param releases マニフェストのリリースの id（新しい順）
     * @param paperVersions Paper にビルドがある全バージョン
     * @param hasBuild そのバージョンに、しきい値で許されるビルドが 1 つでもあるか（古いビルドも含める）
     */
    suspend fun select(
        spec: String,
        releases: List<String>,
        paperVersions: Set<String>,
        hasBuild: suspend (version: String, threshold: PaperChannel) -> Boolean,
        maxVersions: Int = DEFAULT_MAX_VERSIONS,
        paperChannel: String = DEFAULT_PAPER_CHANNEL,
    ): List<String> {
        // 不正なチャンネルはバージョンを見る前に弾く
        val threshold = parseChannel(paperChannel)
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) throw VersionError("the version spec is empty")
        if (trimmed == LATEST) return listOf(selectLatest(releases, paperVersions, hasBuild, threshold))
        if (trimmed !in releases && trimmed in paperVersions) {
            // 1.21.11-rc3 のようなプレリリースは "-" を含むので、範囲と解釈する前に弾く
            throw VersionError("$trimmed is not a Minecraft release; pre-releases and snapshots are not supported")
        }
        if ('-' !in trimmed) return listOf(selectSingle(releases, paperVersions, hasBuild, trimmed, threshold))
        val (lower, upper) = trimmed.split("-", limit = 2).map { it.trim() }
        val selected = selectRange(releases, paperVersions, hasBuild, lower, upper.ifEmpty { null }, threshold)
        if (selected.size > maxVersions) {
            throw VersionError(
                "$trimmed resolves to ${selected.size} versions, more than the maximum of $maxVersions; " +
                    "narrow the range or raise --max-versions",
            )
        }
        return selected
    }

    /** 明示した 1 つのバージョンを確かめる。許容するビルドが無ければ --paper-channel で緩められることを示す。 */
    private suspend fun selectSingle(
        releases: List<String>,
        paperVersions: Set<String>,
        hasBuild: suspend (String, PaperChannel) -> Boolean,
        version: String,
        threshold: PaperChannel,
    ): String {
        ensureSupported(releases, version)
        if (version !in paperVersions) throw VersionError("Paper has no builds for $version")
        if (!hasBuild(version, threshold)) throw VersionError(noAcceptedBuildMessage(version, threshold))
        return version
    }

    /** 新しいリリースから順に見て、許容するビルドがある最初のバージョン。 */
    private suspend fun selectLatest(
        releases: List<String>,
        paperVersions: Set<String>,
        hasBuild: suspend (String, PaperChannel) -> Boolean,
        threshold: PaperChannel,
    ): String {
        for (version in releases) {
            // 公開直後のバージョンは Paper が ALPHA / BETA のことが多いので、しきい値に満たなければ飛ばす
            if (version in paperVersions && hasBuild(version, threshold)) return version
        }
        throw VersionError("no Minecraft release has a ${describeChannels(threshold)} Paper build")
    }

    /** lower から upper（null なら最新）までのリリースのうち、許容するビルドがあるものを古い順で返す。 */
    private suspend fun selectRange(
        releases: List<String>,
        paperVersions: Set<String>,
        hasBuild: suspend (String, PaperChannel) -> Boolean,
        lower: String,
        upper: String?,
        threshold: PaperChannel,
    ): List<String> {
        if (lower.isEmpty()) throw VersionError("a version range needs a lower bound, such as 1.21.6-")
        ensureSupported(releases, lower)
        val (newest, oldest) = rangeIndices(releases, lower, upper)
        // Paper にビルドが無いバージョンは問い合わせない（builds/latest が 404 になる）
        val selected = releases.subList(newest, oldest + 1).filter { it in paperVersions && hasBuild(it, threshold) }
        if (selected.isEmpty()) {
            throw VersionError("no release between $lower and ${upper ?: LATEST} has a ${describeChannels(threshold)} Paper build")
        }
        return selected.reversed()
    }

    /** 許容するチャンネルを "stable" や "stable/beta" のように表す。 */
    private fun describeChannels(threshold: PaperChannel): String =
        PaperBuildPicker.acceptedChannels(threshold).joinToString("/") { it.lowercase(Locale.ROOT) }

    /** しきい値を満たすビルドが無いときのメッセージ。緩める方法を示す。 */
    fun noAcceptedBuildMessage(version: String, threshold: PaperChannel): String {
        val channels = PaperBuildPicker.acceptedChannels(threshold).joinToString("/")
        val name = threshold.name.lowercase(Locale.ROOT)
        val message = "Paper has no $channels build for $version (--paper-channel $name)"
        // まだ緩められるときだけ、より不安定なチャンネルを許す方法を示す
        val looser = PAPER_CHANNELS.drop(threshold.ordinal + 1)
        if (looser.isEmpty()) return message
        return "$message; use --paper-channel ${looser.joinToString(" or ")} (the paper-channel input) to accept less stable builds"
    }

    /** 範囲の両端をマニフェストの添字（newest, oldest）にする。新しい順なので newest <= oldest。 */
    private fun rangeIndices(releases: List<String>, lower: String, upper: String?): Pair<Int, Int> {
        ensureRelease(releases, lower)
        if (upper != null) ensureRelease(releases, upper)
        val newest = if (upper != null) releases.indexOf(upper) else 0
        val oldest = releases.indexOf(lower)
        if (newest > oldest) throw VersionError("$lower is newer than $upper")
        return newest to oldest
    }

    /** マニフェストのリリースに含まれることを確かめる（スナップショットなどは対象外）。 */
    private fun ensureRelease(releases: List<String>, version: String) {
        if (version !in releases) throw VersionError("$version is not a Minecraft release in the Mojang version manifest")
    }

    /** MIN_SUPPORTED 以降のリリースであることを、マニフェストの並びで確かめる。 */
    private fun ensureSupported(releases: List<String>, version: String) {
        ensureRelease(releases, version)
        // マニフェストは新しい順なので、添字が大きいほど古い
        if (MIN_SUPPORTED in releases && releases.indexOf(version) > releases.indexOf(MIN_SUPPORTED)) {
            throw VersionError("fukurou supports Minecraft $MIN_SUPPORTED or later; got $version")
        }
    }
}
