package party.morino.fukurou.version

/**
 * "1.21.9"（単一）/ "1.21.6-"（上限なし）/ "1.21.6-1.21.11"（両端を含む）形式の範囲（versions.py parse_spec の移植）。
 *
 * @MinecraftVersions が使う。Python 版と違いマニフェストを使わず、[MinecraftVersion] の順序で判定する。
 *
 * @property lower 下限（含む）
 * @property upper 上限（含む）。null なら上限なし
 */
public class VersionSpec private constructor(
    public val lower: MinecraftVersion,
    public val upper: MinecraftVersion?,
) {
    /** v がこの範囲に含まれるか。 */
    public fun contains(v: MinecraftVersion): Boolean = v >= lower && (upper == null || v <= upper)

    override fun toString(): String = when (upper) {
        lower -> lower.id
        null -> "${lower.id}-"
        else -> "${lower.id}-${upper.id}"
    }

    public companion object {
        /** "latest" はチャンネル（通信）が無いと決まらないため受け付けない。 */
        private const val LATEST = "latest"

        /** 構文を検証して分解する。不正なら IllegalArgumentException（Python の VersionError と同じ文言）。 */
        public fun parse(spec: String): VersionSpec {
            val text = spec.trim()
            require(text.isNotEmpty()) { "the version spec is empty" }
            require(text != LATEST) { "'$LATEST' is not allowed here; use a version or a range such as 1.21.6-" }
            // "-" が無ければ単一のバージョン（lower == upper）
            if ('-' !in text) {
                val single = MinecraftVersion(text)
                return VersionSpec(single, single)
            }
            val lower = text.substringBefore('-').trim()
            val upper = text.substringAfter('-').trim()
            require(lower.isNotEmpty()) { "a version range needs a lower bound, such as 1.21.6-" }
            require('-' !in upper) { "'$text' is not a version or a range such as 1.21.6-1.21.11" }
            val low = MinecraftVersion(lower)
            val high = upper.takeIf { it.isNotEmpty() }?.let(::MinecraftVersion)
            // 上下が逆の範囲は何にも一致しないので、書き間違いとして知らせる
            require(high == null || low <= high) { "$lower is newer than $upper" }
            return VersionSpec(low, high)
        }
    }
}
