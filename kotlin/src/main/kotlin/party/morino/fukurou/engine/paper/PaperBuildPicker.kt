package party.morino.fukurou.engine.paper

import party.morino.fukurou.server.paper.PaperChannel
import party.morino.fukurou.version.MinecraftVersion
import java.util.Locale

/** 使う Paper のビルドを選ぶ（paper.py:56-66,111-128）。純粋。 */
internal object PaperBuildPicker {
    /** しきい値で許されるビルドのチャンネル（安定している順、大文字）。Alpha なら 3 つすべて。 */
    fun acceptedChannels(threshold: PaperChannel): List<String> =
        PaperChannel.entries.take(threshold.ordinal + 1).map { it.name.uppercase(Locale.ROOT) }

    /** ビルドのチャンネル（"STABLE" など）がしきい値で許されるか。未知のチャンネルは許さない。 */
    fun channelAccepted(channel: String, threshold: PaperChannel): Boolean =
        channel.uppercase(Locale.ROOT) in acceptedChannels(threshold)

    /** API の絞り込みに頼り切らず、ここでもチャンネルと id を確かめて、許される最も新しいビルドを選ぶ。無ければ null。 */
    fun pick(builds: List<PaperBuild>, threshold: PaperChannel): PaperBuild? =
        builds.filter { channelAccepted(it.channel, threshold) }.maxByOrNull { it.id }

    /** しきい値を満たすビルドが無いときのメッセージ。まだ緩められるなら、緩める方法（-Pfukurou.paperChannel）を示す。 */
    fun noAcceptedBuildMessage(version: MinecraftVersion, threshold: PaperChannel): String {
        val channels = acceptedChannels(threshold).joinToString("/")
        val name = threshold.name.lowercase(Locale.ROOT)
        val looser = PaperChannel.entries.drop(threshold.ordinal + 1).map { it.name.lowercase(Locale.ROOT) }
        val message = "Paper has no $channels build for $version (fukurou.paperChannel=$name)"
        if (looser.isEmpty()) return message
        return "$message; pass -Pfukurou.paperChannel=${looser.joinToString("|")} to accept less stable builds"
    }

    /** channel=STABLE&channel=BETA… の問い合わせ（指定したいずれかのチャンネルのビルドが新しい順に返る）。 */
    fun channelQuery(threshold: PaperChannel): String = acceptedChannels(threshold).joinToString("&") { "channel=$it" }
}
