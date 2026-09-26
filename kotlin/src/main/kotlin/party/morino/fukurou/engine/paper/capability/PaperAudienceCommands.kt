package party.morino.fukurou.engine.paper.capability

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import net.kyori.adventure.title.TitlePart
import party.morino.fukurou.engine.paper.command.ComponentCodec
import party.morino.fukurou.engine.paper.command.ErrorResponse
import party.morino.fukurou.spi.capability.AudienceCommands
import party.morino.fukurou.spi.model.CommandCall
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Adventure の Audience の操作を Paper（バニラ）のコマンドにする（§1.9 の表）。純粋。
 *
 * @property codec Component をサーバーのバージョンに合う JSON にする
 */
internal class PaperAudienceCommands(private val codec: ComponentCodec) : AudienceCommands {
    override fun message(player: String, message: Component): List<CommandCall> =
        listOf(CommandCall("tellraw $player ${codec.encode(message)}", player = player))

    override fun actionBar(player: String, message: Component): List<CommandCall> =
        listOf(CommandCall("title $player actionbar ${codec.encode(message)}", player = player))

    override fun <T : Any> titlePart(player: String, part: TitlePart<T>, value: T): List<CommandCall> {
        // TitlePart の定数は単一のインスタンスなので、同一性で見分ける
        val command = when (part) {
            TitlePart.TITLE -> "title $player title ${codec.encode(value as Component)}"
            TitlePart.SUBTITLE -> "title $player subtitle ${codec.encode(value as Component)}"
            TitlePart.TIMES -> {
                val times = value as Title.Times
                "title $player times ${ticks(times.fadeIn())} ${ticks(times.stay())} ${ticks(times.fadeOut())}"
            }
            else -> throw IllegalArgumentException("unknown title part $part")
        }
        return listOf(CommandCall(command, player = player))
    }

    override fun clearTitle(player: String): List<CommandCall> = listOf(CommandCall("title $player clear", player = player))

    override fun resetTitle(player: String): List<CommandCall> = listOf(CommandCall("title $player reset", player = player))

    override fun playSound(player: String, sound: Sound, at: Triple<Double, Double, Double>?): List<CommandCall> {
        val key = sound.name().asString()
        val source = sourceName(sound.source())
        // seed に当たる引数はコマンドに無いので使わない
        val tail = "${number(sound.volume())} ${number(sound.pitch())}"
        val command = if (at == null) {
            // Emitter.self(): プレイヤーの位置で、プレイヤーだけに聞かせる
            "execute as $player at @s run playsound $key $source @s ~ ~ ~ $tail"
        } else {
            "playsound $key $source $player ${number(at.first)} ${number(at.second)} ${number(at.third)} $tail"
        }
        return listOf(CommandCall(command, player = player))
    }

    override fun stopSound(player: String, stop: SoundStop): List<CommandCall> {
        val source = stop.source()?.let(::sourceName)
        val sound = stop.sound()?.asString()
        // stopsound <対象> [<source>|*] [<sound>]。音だけを指定するときは source を * にする
        val arguments = when {
            source == null && sound == null -> ""
            sound == null -> " $source"
            else -> " ${source ?: "*"} $sound"
        }
        return listOf(CommandCall("stopsound $player$arguments", player = player))
    }

    override fun bossBarCreate(id: Key, bar: BossBar): List<CommandCall> =
        listOf(CommandCall("bossbar add ${id.asString()} ${codec.encode(bar.name())}")) +
            CommandCall("bossbar set ${id.asString()} max $BOSS_BAR_MAX") +
            settings(id, bar, includeName = false)

    override fun bossBarUpdate(id: Key, bar: BossBar): List<CommandCall> = settings(id, bar, includeName = true)

    override fun bossBarViewers(id: Key, players: List<String>): List<CommandCall> {
        val bossbar = "bossbar set ${id.asString()} players"
        return when (players.size) {
            // 対象を省くと誰にも見せない
            0 -> listOf(CommandCall(bossbar))
            1 -> listOf(CommandCall("$bossbar ${players.single()}", player = players.single()))
            else -> {
                // players は対象を 1 つしか取らないので、見る人にタグを付けてセレクターでまとめて指定する
                val tag = viewerTag(id)
                listOf(CommandCall("tag @a remove $tag", ignore = TAG_IGNORE)) +
                    players.map { CommandCall("tag $it add $tag", player = it) } +
                    CommandCall("$bossbar @a[tag=$tag]")
            }
        }
    }

    override fun bossBarRemove(id: Key): List<CommandCall> =
        listOf(
            CommandCall("bossbar remove ${id.asString()}"),
            // 複数人で見ていたときに付けたタグを残さない
            CommandCall("tag @a remove ${viewerTag(id)}", ignore = TAG_IGNORE),
        )

    /** 名前（任意）・色・形・値の bossbar set。 */
    private fun settings(id: Key, bar: BossBar, includeName: Boolean): List<CommandCall> {
        val prefix = "bossbar set ${id.asString()}"
        return listOfNotNull(
            if (includeName) CommandCall("$prefix name ${codec.encode(bar.name())}") else null,
            CommandCall("$prefix color ${bar.color().name.lowercase(Locale.ROOT)}"),
            // PROGRESS → progress、NOTCHED_6 → notched_6 のように、列挙の名前を小文字にしたものがバニラの値
            CommandCall("$prefix style ${bar.overlay().name.lowercase(Locale.ROOT)}"),
            CommandCall("$prefix value ${(bar.progress() * BOSS_BAR_MAX).roundToInt()}"),
        )
    }

    /** 音の source（MASTER → master）。バニラの名前は列挙の名前を小文字にしたもの。 */
    private fun sourceName(source: Sound.Source): String = source.name.lowercase(Locale.ROOT)

    /** 期間を tick（1 tick = 50 ms）にする。 */
    private fun ticks(duration: java.time.Duration): Long = duration.toMillis() / MILLIS_PER_TICK

    /** 数値の末尾の 0 を落とす（1.0 → "1"、0.5 → "0.5"）。Float は toString 経由で丸めの誤差を出さない。 */
    private fun number(value: Number): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    /** 複数の人に見せるときに使うタグ（fukurou:bar-1 → fukurou.bar-1）。 */
    private fun viewerTag(id: Key): String = "${id.namespace()}.${id.value()}".replace(Regex("[^A-Za-z0-9._+-]"), "_")

    private companion object {
        /** ボスバーの最大値。progress（0..1）を 1000 段階にする。 */
        private const val BOSS_BAR_MAX = 1000

        /** 1 tick のミリ秒。 */
        private const val MILLIS_PER_TICK = 50L

        /** 誰もタグを持たない・誰も参加していないときの応答。どちらも失敗ではない。 */
        private val TAG_IGNORE = listOf("No entity was found", ErrorResponse.PLAYER_MISSING)
    }
}
