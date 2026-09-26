package party.morino.fukurou.player

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.identity.Identified
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import party.morino.fukurou.log.LogView
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location
import party.morino.fukurou.world.Worlds
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 参加済みのプレイヤー。クライアントの入力（xdotool）とクライアントログの照合、サーバー側の操作（種類の能力経由）を持つ。
 *
 * Adventure の Audience として、このプレイヤーにメッセージ・タイトル・音・ボスバーを送れる（§1.9）。
 * Adventure の契約（未対応の操作は何もしない）と違い、サーバーの種類が対応していない操作は
 * [party.morino.fukurou.error.UnsupportedCapabilityException] を投げる。黙って無視すると、
 * タイトルの写っていないスクリーンショットで緑のテストになってしまうため。
 *
 * クライアントを起動し直しても同じインスタンスのまま中身を付け替える。
 */
public interface Player : Audience, Identified {
    /** 参加前の宣言。 */
    public val profile: PlayerProfile

    /** プレイヤー名（= profile.name）。 */
    public val name: String

    /** オフラインモードの UUID（UUID.nameUUIDFromBytes("OfflinePlayer:<name>")）。 */
    public val uuid: UUID

    /** join を呼んだサーバー。 */
    public val server: GameServer

    /** クライアントの logs/latest.log。テストごとの窓で見る。 */
    public val log: LogView

    // --- クライアントへの入力（このプレイヤー専用の Xvfb に xdotool で送る）。ここでは Adventure を使わない ---

    /** キーを 1 回押して離す。 */
    public suspend fun pressKey(key: KeySym)

    /** キーを同時に押す（pressChord(KeySym.F3 + KeySym.D)）。 */
    public suspend fun pressChord(chord: Chord)

    /** 文字列をキーボードで入力する。 */
    public suspend fun typeText(text: String)

    /** T でチャット欄を開き、0.5 秒待って入力し、Return で送る（player_session.py:87）。 */
    public suspend fun chat(text: String)

    /**
     * チャット欄からコマンドを送り（"/" は付けても付けなくてもよい）、種類の CommandEcho が返すログ行を待つ。
     * 送信前にサーバーログを mark するので、同じテストの前のコマンドの行には一致しない。
     */
    public suspend fun sendCommand(command: String, timeout: Duration = 10.seconds): LogMatch

    /** F5 を (target - current) mod 3 回押して視点を合わせる。 */
    public suspend fun perspective(perspective: Perspective)

    /** F2 で撮影し、tests/<id>/screenshots/<player>/<name>.png に保存する。 */
    public suspend fun screenshot(name: String): Screenshot

    // --- サーバー側の操作（PlayerCommands 能力。無ければ UnsupportedCapabilityException） ---

    /** 指定の位置へテレポートする。 */
    public suspend fun teleport(location: Location)

    /** 座標を指定してテレポートする。yaw と pitch は両方指定するか両方省略する。 */
    public suspend fun teleport(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float? = null,
        pitch: Float? = null,
        world: Key = Worlds.OVERWORLD,
    )

    /** ゲームモードを変える。 */
    public suspend fun gamemode(mode: GameMode)

    /** op にする。 */
    public suspend fun op()

    /** op を外す。 */
    public suspend fun deop()

    /** アイテムを与える。 */
    public suspend fun give(item: Key, count: Int = 1)

    // --- クライアントログの照合（チャットは latest.log に "[CHAT] …" の行で出る） ---

    /** クライアントログの現在の末尾。 */
    public fun mark(): LogMark

    /** \[CHAT\] の行で pattern に一致するものが出るまで待つ。 */
    public suspend fun awaitChat(pattern: Regex, timeout: Duration = 60.seconds, after: LogMark? = null): LogMatch

    /** PlainTextComponentSerializer でプレーンテキストにし、Regex.escape して \[CHAT\] の行と照合する。 */
    public suspend fun awaitChat(message: Component, timeout: Duration = 60.seconds, after: LogMark? = null): LogMatch

    /** \[CHAT\] の行で pattern に一致するものがあれば LogAssertionError。 */
    public fun assertNoChat(pattern: Regex, after: LogMark? = null)

    /** message をプレーンテキストにしたチャットの行があれば LogAssertionError。 */
    public fun assertNoChat(message: Component, after: LogMark? = null)
}
