package party.morino.fukurou.engine.audience

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import party.morino.fukurou.engine.session.ServerInstance
import party.morino.fukurou.spi.capability.AudienceCommands
import party.morino.fukurou.spi.model.CommandCall
import java.util.IdentityHashMap

/**
 * Adventure の BossBar をサーバーのボスバー（bossbar コマンド）に写す（§1.9）。
 *
 * BossBar のインスタンスごとに fukurou:bar-<n> を 1 つ作り、見ているプレイヤーの一覧を保つ。名前・進捗・色・形の変更は
 * BossBar.Listener で受け、bossbar set を送り直す（コールバックのスレッドで止めて送る。失敗は harness.log にだけ残す）。
 *
 * @param server ボスバーを表示するサーバー
 */
internal class BossBarSync(private val server: ServerInstance) {
    /** BossBar → サーバー上の id。 */
    private val ids = IdentityHashMap<BossBar, Key>()

    /** BossBar → 見ているプレイヤー（表示順）。 */
    private val viewers = IdentityHashMap<BossBar, MutableList<String>>()

    /** BossBar → 登録した Listener。 */
    private val listeners = IdentityHashMap<BossBar, BossBar.Listener>()

    /** 次の id の番号。 */
    private var next = 0

    /** プレイヤーに表示する。初めてのバーならサーバーに作る。 */
    @Synchronized
    fun show(bar: BossBar, player: String) {
        val commands = commands("showBossBar")
        val calls = mutableListOf<CommandCall>()
        val id = ids[bar] ?: create(bar, commands, calls)
        val list = viewers.getValue(bar)
        // 既に見ているなら送るものは無い（作ったばかりなら作成のコマンドだけ送る）
        if (player !in list) {
            list += player
            calls += commands.bossBarViewers(id, list.toList())
        }
        AudienceDispatcher.execute(server, calls)
    }

    /** プレイヤーから隠す。誰も見ていなければサーバーから消す。 */
    @Synchronized
    fun hide(bar: BossBar, player: String) {
        val commands = commands("hideBossBar")
        val id = ids[bar] ?: return
        val list = viewers.getValue(bar)
        if (!list.remove(player)) return
        if (list.isNotEmpty()) {
            AudienceDispatcher.execute(server, commands.bossBarViewers(id, list.toList()))
            return
        }
        // 最後の 1 人が隠したら、サーバーのバーと Listener を片付ける
        forget(bar)
        AudienceDispatcher.execute(server, commands.bossBarRemove(id))
    }

    /** 新しいセッション（サーバーを作り直した）ではサーバーのバーが無いので、覚えている対応を捨てる。 */
    @Synchronized
    fun reset() {
        ids.keys.toList().forEach(::forget)
        next = 0
    }

    /** サーバーにバーを作る。 */
    private fun create(bar: BossBar, commands: AudienceCommands, calls: MutableList<CommandCall>): Key {
        val id = Key.key(NAMESPACE, "bar-${next++}")
        ids[bar] = id
        viewers[bar] = mutableListOf()
        calls += commands.bossBarCreate(id, bar)
        // フラグ（霧・暗転など）に対応するコマンドは無い
        if (bar.flags().isNotEmpty()) server.warn("boss bar $id: flags ${bar.flags()} have no command and are ignored")
        val listener = Listener(bar)
        listeners[bar] = listener
        bar.addListener(listener)
        return id
    }

    /** バーの対応と Listener を捨てる。 */
    private fun forget(bar: BossBar) {
        listeners.remove(bar)?.let(bar::removeListener)
        ids.remove(bar)
        viewers.remove(bar)
    }

    /** 変更をサーバーへ送り直す。 */
    private fun update(bar: BossBar) {
        val calls = synchronized(this) {
            val id = ids[bar] ?: return
            commands("BossBar.Listener").bossBarUpdate(id, bar)
        }
        try {
            AudienceDispatcher.execute(server, calls)
        } catch (error: Exception) {
            // コールバックは利用者のコードから呼ばれるので、例外を返さず記録にとどめる
            server.warn("could not update boss bar ${ids[bar]}: ${error.message}")
        }
    }

    /** AudienceCommands の能力。 */
    private fun commands(method: String): AudienceCommands =
        server.capability(AudienceCommands::class) ?: AudienceDispatcher.unsupported(server, method)

    /**
     * バーの変更を受け取る Listener。
     *
     * @param bar 見ているバー
     */
    private inner class Listener(private val bar: BossBar) : BossBar.Listener {
        /** 名前の変更。 */
        override fun bossBarNameChanged(bar: BossBar, oldName: Component, newName: Component): Unit = update(this.bar)

        /** 進捗の変更。 */
        override fun bossBarProgressChanged(bar: BossBar, oldProgress: Float, newProgress: Float): Unit = update(this.bar)

        /** 色の変更。 */
        override fun bossBarColorChanged(bar: BossBar, oldColor: BossBar.Color, newColor: BossBar.Color): Unit = update(this.bar)

        /** 形の変更。 */
        override fun bossBarOverlayChanged(bar: BossBar, oldOverlay: BossBar.Overlay, newOverlay: BossBar.Overlay): Unit = update(this.bar)

        /** フラグにはコマンドが無い。 */
        override fun bossBarFlagsChanged(bar: BossBar, flagsAdded: Set<BossBar.Flag>, flagsRemoved: Set<BossBar.Flag>) {
            server.warn("boss bar ${ids[this.bar]}: flags have no command; the change is ignored")
        }
    }

    /** 定数。 */
    private companion object {
        /** fukurou が作るボスバーの名前空間。 */
        const val NAMESPACE = "fukurou"
    }
}
