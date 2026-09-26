package party.morino.fukurou

/** Xvfb や xdotool などホストの道具が足りないときの扱い（fukurou.missingHost）。 */
public enum class MissingHostPolicy {
    /** 不足を SetupException にする（既定）。 */
    FAIL,

    /** 不足をテストの中断（skipped）にする。ローカルで一部だけ走らせたいとき用。 */
    SKIP,
}
