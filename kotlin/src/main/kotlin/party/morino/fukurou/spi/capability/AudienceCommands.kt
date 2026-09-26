package party.morino.fukurou.spi.capability

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.TitlePart
import party.morino.fukurou.spi.Capability
import party.morino.fukurou.spi.FukurouSpi
import party.morino.fukurou.spi.model.CommandCall

/** Adventure の Audience の操作をコマンドに置き換える計画（純粋、§1.9 の表）。 */
@FukurouSpi
public interface AudienceCommands : Capability {
    /** チャットのメッセージ（tellraw）。 */
    public fun message(player: String, message: Component): List<CommandCall>

    /** アクションバー。 */
    public fun actionBar(player: String, message: Component): List<CommandCall>

    /** タイトルの一部（times / subtitle / title）。 */
    public fun <T : Any> titlePart(player: String, part: TitlePart<T>, value: T): List<CommandCall>

    /** タイトルを消す。 */
    public fun clearTitle(player: String): List<CommandCall>

    /** タイトルの設定を戻す。 */
    public fun resetTitle(player: String): List<CommandCall>

    /** 音を鳴らす。at が null ならプレイヤーの位置（Emitter.self()）。 */
    public fun playSound(player: String, sound: Sound, at: Triple<Double, Double, Double>?): List<CommandCall>

    /** 音を止める。 */
    public fun stopSound(player: String, stop: SoundStop): List<CommandCall>

    /** ボスバーを作る（名前・色・形・値）。 */
    public fun bossBarCreate(id: Key, bar: BossBar): List<CommandCall>

    /** ボスバーの名前・色・形・値を更新する。 */
    public fun bossBarUpdate(id: Key, bar: BossBar): List<CommandCall>

    /** ボスバーを見るプレイヤーを設定する。 */
    public fun bossBarViewers(id: Key, players: List<String>): List<CommandCall>

    /** ボスバーを消す。 */
    public fun bossBarRemove(id: Key): List<CommandCall>
}
