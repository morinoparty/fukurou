package party.morino.fukurou.server.paper

/** Paper のビルドのチャンネル。安定している順（paper.py:10-13）。 */
public enum class PaperChannel {
    /** 安定版。 */
    Stable,

    /** ベータ。 */
    Beta,

    /** アルファ（新しいバージョンの公開直後）。 */
    Alpha,
    ;

    public companion object {
        /** 大文字小文字を問わずに読む（"stable" / "BETA" など）。不正なら IllegalArgumentException。 */
        public fun parse(text: String): PaperChannel =
            entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown Paper channel '$text'; use stable, beta or alpha")
    }
}
