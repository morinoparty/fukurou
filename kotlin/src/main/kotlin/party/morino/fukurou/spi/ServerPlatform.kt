package party.morino.fukurou.spi

import party.morino.fukurou.server.ServerType
import party.morino.fukurou.spi.model.PlatformInfo
import party.morino.fukurou.spi.model.ProvisionRequest
import party.morino.fukurou.spi.model.Provisioned

/** 種類ごとの戦略。1 サーバーにつき 1 インスタンス。セッション（起動）ごとに provision → launch → ready → bind → stop。 */
@FukurouSpi
public interface ServerPlatform {
    /** この戦略を作った種類。 */
    public val type: ServerType

    /** セッションの準備。ダウンロード・ディレクトリ作成・設定の書き込みを行い、起動方法を返す。 */
    public suspend fun provision(request: ProvisionRequest): Provisioned

    /** サーバーログにこの行が出たら起動完了の候補（Paper: "Done ("）。 */
    public val readyPattern: Regex

    /** readyPattern の後の確認（Paper: RCON `list` が成功するまで再試行）。成功で true。 */
    public suspend fun confirmReady(channel: CommandChannel?): Boolean

    /** ポートの取り合いで起動に失敗したことを示す行（Paper: "FAILED TO BIND TO PORT"）。null なら再試行しない。 */
    public val portConflictPattern: Regex? get() = null

    /** サーバーログでこのプレイヤーの参加を示す行（Paper: \b<name> joined the game）。 */
    public fun joinedPattern(player: String): Regex

    /** コマンド経路（Paper: RCON）。無い種類は null。 */
    public fun openChannel(provisioned: Provisioned): CommandChannel?

    /** 応答のエラー判定（Paper: isolation.py:21 ERROR_RESPONSE）。 */
    public val responseCheck: ResponseCheck

    /** 起動中のセッションに能力を結びつける。 */
    public fun bind(session: PlatformSession): Capabilities

    /** 穏やかな停止の要求（Paper: RCON "stop"）。この後エンジンが 60 s 待ち、プロセスグループを止める。 */
    public suspend fun requestStop(channel: CommandChannel?)

    /** result.minecraft / java / plugins に書く情報。 */
    public fun describe(provisioned: Provisioned): PlatformInfo
}
