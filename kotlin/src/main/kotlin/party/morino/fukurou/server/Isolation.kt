package party.morino.fukurou.server

import party.morino.fukurou.world.GameMode
import party.morino.fukurou.world.Location
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** テスト同士を隔離する方法。 */
public sealed interface Isolation {
    /**
     * isolation.py:70 の reset_commands 相当を各テストの前に送る（既定）。種類の ResetPlanner が必要。
     *
     * @property arena 空気で埋め直す領域。null ならブロックに触れない（スイートの arena: false）
     * @property gamemode リセット時に設定するゲームモード
     * @property settle リセット後にクライアントがブロック更新を受け取るまで待つ時間
     * @property spawns プレイヤーごとの初期位置。無ければ isolation.py:55 の既定のスロット
     * @property normalizeView F3+D でチャットを消し、F5 で一人称に戻すか
     */
    public data class Reset(
        val arena: Arena? = Arena(size = 32, height = 24),
        val gamemode: GameMode = GameMode.SURVIVAL,
        val settle: Duration = 2.seconds,
        val spawns: Map<String, Location> = emptyMap(),
        val normalizeView: Boolean = true,
    ) : Isolation

    /** テストごとにサーバーを作り直す（kind=fresh-server の新しいセッション）。 */
    public data object FreshServer : Isolation

    /** 何もしない。 */
    public data object None : Isolation
}

/**
 * リセット時に空気で埋める領域の大きさ（x, z は原点を中心に size、y は地面から height）。
 *
 * fill は 1 回 32768 ブロックまでなので、それを超える大きさは作れないようにする。
 *
 * @property size x, z 方向の幅
 * @property height 地面からの高さ
 */
public data class Arena(val size: Int, val height: Int) {
    init {
        require(size > 0 && height > 0 && size.toLong() * size * height <= 32_768) {
            "arena $size x $size x $height must be positive and at most 32768 blocks (one fill)"
        }
    }
}
