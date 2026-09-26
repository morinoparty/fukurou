package party.morino.fukurou.engine.paper.capability

import party.morino.fukurou.engine.paper.command.VanillaCommands
import party.morino.fukurou.player.PlayerProfile
import party.morino.fukurou.server.Arena
import party.morino.fukurou.server.Isolation
import party.morino.fukurou.spi.capability.ResetPlanner
import party.morino.fukurou.spi.model.CommandCall
import party.morino.fukurou.world.Location
import party.morino.fukurou.world.Worlds

/**
 * テストの前のリセットのコマンド列（run/isolation.py:70-130 reset_commands の移植）。純粋。
 *
 * 参加者は参加済みの全員で、駐機（parked）は無い。順序に意味がある:
 * 先に参加プレイヤーを移してからアリーナを fill する（逆だと足場が消えて落下し、減った体力がスクリーンショットに残る）。
 */
internal object VanillaResetPlanner : ResetPlanner {
    /** 既定のスロットの y（isolation.py SLOT_Y）。 */
    private const val SLOT_Y = -60.0

    /** 既定のスロットの z。原点の列（x=0）は利用者の fixture が柱を建てることが多いので避ける。 */
    private const val SLOT_Z = -8.5

    /** フラットワールドの地表。アリーナはここから上を空気にし、下を地面として敷き直す。 */
    private const val GROUND_Y = -60

    /**
     * instant_health の回復量は 4 << amplifier。Java はシフト量を 5 ビットに丸めるため 255 では 0 になる。
     * 10 なら 4096 HP で十分。saturation も同じ値にする。
     */
    const val EFFECT_AMPLIFIER: Int = 10

    override fun plan(participants: List<PlayerProfile>, reset: Isolation.Reset): List<CommandCall> {
        // スロット番号は参加順で固定し、テストが変わっても同じプレイヤーが同じ場所に立つようにする
        val spawns = participants.mapIndexed { index, profile ->
            profile.name to (reset.spawns[profile.name] ?: defaultSlot(index))
        }.toMap()
        val calls = mutableListOf<CommandCall>()

        // 1. 参加プレイヤーを移す（fill より前）
        for (profile in participants) {
            calls += VanillaCommands.gamemode(profile.name, reset.gamemode)
            calls += teleport(profile.name, spawns.getValue(profile.name))
        }
        // 2. アリーナを空気にし、地面を敷き直し、残ったエンティティを消す
        reset.arena?.let { calls += arenaCommands(it) }
        // 3. ワールドの時刻と天候
        calls += CommandCall("time set noon")
        calls += VanillaCommands.weatherClear()
        // 4. 参加プレイヤーの状態。effect clear は give より前（後だと saturation が最初の tick の前に消える）
        for (profile in participants) {
            val name = profile.name
            calls += listOf(
                CommandCall("clear $name", player = name),
                CommandCall("effect clear $name", player = name),
                // 末尾の true は粒子を隠す（isolation.py と同じ）
                CommandCall("effect give $name minecraft:instant_health 1 $EFFECT_AMPLIFIER true", player = name),
                CommandCall("effect give $name minecraft:saturation 1 $EFFECT_AMPLIFIER true", player = name),
                CommandCall("experience set $name 0 points", player = name),
                CommandCall("experience set $name 0 levels", player = name),
                CommandCall("title $name clear", player = name),
                spawnpoint(name, spawns.getValue(name)),
                if (profile.op) VanillaCommands.op(name) else VanillaCommands.deop(name),
            )
        }
        return calls
    }

    /** spawns に無いプレイヤーの位置。x を 2 ブロックずつずらして横に並べる（isolation.py default_slot）。 */
    fun defaultSlot(index: Int): Location = Location(0.5 + 2 * index, SLOT_Y, SLOT_Z, 0f, 0f)

    /** 原点を中心とした size×size、地表から height ブロックの範囲を空気にし、地面を 4 層で敷き直す（isolation.py arena_commands）。 */
    fun arenaCommands(arena: Arena): List<CommandCall> {
        val low = -(arena.size / 2)
        val high = low + arena.size - 1
        val top = GROUND_Y + arena.height - 1

        fun fill(yFrom: Int, yTo: Int, block: String) = CommandCall("fill $low $yFrom $low $high $yTo $high $block")

        return listOf(
            fill(GROUND_Y, top, "minecraft:air"),
            fill(GROUND_Y - 1, GROUND_Y - 1, "minecraft:grass_block"),
            fill(GROUND_Y - 3, GROUND_Y - 2, "minecraft:dirt"),
            fill(GROUND_Y - 4, GROUND_Y - 4, "minecraft:bedrock"),
            CommandCall("kill @e[type=!player]", ignore = listOf(VanillaCommands.NO_ENTITY_FOUND)),
        )
    }

    /** オーバーワールドは isolation.py と同じ素の tp、それ以外は execute in で次元を決める。 */
    private fun teleport(player: String, to: Location): CommandCall =
        if (to.world == Worlds.OVERWORLD) {
            VanillaCommands.plainTeleport(player, to.toCommandArgs())
        } else {
            VanillaCommands.teleport(player, to)
        }

    /** spawnpoint は角度を 1 つ（yaw）しか取らないため、座標の 3 つだけを使う（isolation.py spawnpoint_of）。 */
    private fun spawnpoint(player: String, spawn: Location): CommandCall {
        val coordinates = Location(spawn.x, spawn.y, spawn.z).toCommandArgs()
        val command = "spawnpoint $player $coordinates"
        // 別の次元のリスポーン地点は、その次元で実行して設定する
        val inWorld = if (spawn.world == Worlds.OVERWORLD) command else "execute in ${spawn.world.asString()} run $command"
        return CommandCall(inWorld, player = player)
    }
}
