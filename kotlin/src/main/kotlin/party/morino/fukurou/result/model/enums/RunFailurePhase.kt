package party.morino.fukurou.result.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** run 全体を止めた失敗の段階。これがあると残りのテストは skipped になる。 */
@Serializable
public enum class RunFailurePhase {
    /** 準備（EULA・ホスト・設定・ダウンロード）。 */
    @SerialName("setup")
    SETUP,

    /** サーバーの起動。 */
    @SerialName("server-start")
    SERVER_START,

    /** クライアントの参加。 */
    @SerialName("client-join")
    CLIENT_JOIN,

    /** 実行中のサーバーの停止。 */
    @SerialName("server")
    SERVER,

    /** 後始末。 */
    @SerialName("teardown")
    TEARDOWN,

    /** 中断（Gradle のキャンセルや SIGTERM）。 */
    @SerialName("interrupted")
    INTERRUPTED,
}
