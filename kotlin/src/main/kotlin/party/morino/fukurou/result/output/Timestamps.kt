package party.morino.fukurou.result.output

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * result.json の時刻の書式（result/recorder.py:60-76）。
 *
 * run とテストの時刻は秒精度、ステップの時刻はミリ秒精度の UTC（末尾 Z）で表す。
 */
internal object Timestamps {
    /** 秒精度の書式。契約の例と同じ 2026-09-24T03:02:14Z の形。 */
    private val SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** ミリ秒精度の書式。parallel のステップの重なりは秒精度では見えないため。 */
    private val MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** run・テスト・セッションの時刻（秒精度）。端数は丸めずに切り捨てる（Python の strftime と同じ）。 */
    fun seconds(moment: Instant): String = SECONDS.format(moment.truncatedTo(ChronoUnit.SECONDS))

    /** ステップの時刻（ミリ秒精度）。マイクロ秒以下は切り捨てる（microsecond // 1000 と同じ）。 */
    fun millis(moment: Instant): String = MILLIS.format(moment.truncatedTo(ChronoUnit.MILLIS))
}
