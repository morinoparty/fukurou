package party.morino.fukurou.spi.capability

import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.FukurouSpi

/** プレイヤーがチャット欄から送ったコマンドがサーバーログに残す行。 */
@FukurouSpi
public interface CommandEcho : Capability {
    /** プレイヤーがチャット欄からコマンドを送ったときのサーバーログ（Paper: "<name> issued server command: /<cmd>"）。 */
    public fun issuedCommand(player: String, commandWithoutSlash: String): Regex
}
