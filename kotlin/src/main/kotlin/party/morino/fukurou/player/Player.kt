package party.morino.fukurou.player

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.identity.Identified
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import party.morino.fukurou.log.LogMark
import party.morino.fukurou.log.LogMatch
import party.morino.fukurou.log.LogView
import party.morino.fukurou.server.GameServer
import party.morino.fukurou.state.PlayerSnapshot
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

    /** T でチャット欄を開き、0.5 秒待って入力し、Return で送る。 */
    public suspend fun chat(text: String)

    /**
     * チャット欄からコマンドを送り（"/" は付けても付けなくてもよい）、種類の CommandEcho が返すログ行を待つ。
     * 送信前にサーバーログを mark するので、同じテストの前のコマンドの行には一致しない。
     */
    public suspend fun sendCommand(command: String, timeout: Duration = 10.seconds): LogMatch

    /** F5 を (target - current) mod 3 回押して視点を合わせる。 */
    public suspend fun perspective(perspective: Perspective)

    /**
     * F2 で撮影し、tests/<id>/screenshots/<player>/<name>.png に保存する。
     *
     * 名前はテストの中でプレイヤーごとに一意にする。ただし eventually / awaitUntil の中では試行ごとに同じ名前で撮り直してよく
     * （後の試行が上書きする）、スクリーンショットはその eventually / awaitUntil のステップに結びつく。
     */
    public suspend fun screenshot(name: String): Screenshot

    // --- 押したままの入力とマウス（v3 設計 §2.2）。押したものはテストの終わりに必ず離す ---

    /** キーを押したままにする。 */
    public suspend fun keyDown(key: KeySym)

    /** 押したままのキーを離す。 */
    public suspend fun keyUp(key: KeySym)

    /** キーを duration の間押し続ける（hold_key）。 */
    public suspend fun holdKey(key: KeySym, duration: Duration)

    /** keys を押したまま block を実行し、最後に（例外や取り消しでも）離す。 */
    public suspend fun <T> holding(keys: List<KeySym>, block: suspend () -> T): T

    /** key を押したまま block を実行し、最後に（例外や取り消しでも）離す。 */
    public suspend fun <T> holding(key: KeySym, block: suspend () -> T): T = holding(listOf(key), block)

    /** マウスをウィンドウの座標（1280x720）へ動かす。GUI のカーソルを動かす（ゲーム中の視点の操作には look を使う）。 */
    public suspend fun mouseMove(x: Int, y: Int)

    /** 今のカーソルの位置でクリックする。 */
    public suspend fun click(button: MouseButton = MouseButton.LEFT)

    /** (x, y) へ動かしてクリックする。 */
    public suspend fun click(x: Int, y: Int, button: MouseButton = MouseButton.LEFT)

    /** ボタンを duration の間押し続ける（ブロックの破壊など）。 */
    public suspend fun holdMouse(button: MouseButton, duration: Duration)

    /** ホイールを回す。正で下（ホットバーの右）、負で上。 */
    public suspend fun scroll(steps: Int)

    /** ホットバーのスロット（0〜8）を数字キーで選ぶ。 */
    public suspend fun selectHotbar(slot: Int)

    /** 左クリック（攻撃・破壊）。 */
    public suspend fun attack(): Unit = click(MouseButton.LEFT)

    /** 右クリック（使う・置く）。 */
    public suspend fun useItem(): Unit = click(MouseButton.RIGHT)

    // --- サーバー側の操作（PlayerCommands 能力。無ければ UnsupportedCapabilityException） ---

    /** 位置を変えずに向きを変える（サーバー側の回転。PlayerCommands.rotate）。 */
    public suspend fun look(yaw: Float, pitch: Float)

    /** サーバーから見た状態（エージェント経由の query ステップ）。エージェントが無ければ UnsupportedCapabilityException。 */
    public suspend fun state(): PlayerSnapshot

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
