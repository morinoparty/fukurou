"""テストの前にハーネスが行うリセットのコマンド列を組み立てる純粋関数。

サーバーやクライアントには触れず、TestSpec と参加済みプレイヤーと ResetSpec からコマンドの並びを決めるだけにして、
順序（先に退避してから fill する等）を単体テストで固定できるようにしている。
"""

from dataclasses import dataclass
import re

from fukurou.scenario import ResetSpec, TestSpec

# このテストに参加しないが参加済みのプレイヤーの駐機場所。エンティティの追跡範囲より遠いので画面に出ない
PARKING_SPOT = "0.5 -60 200.5"
# 既定のスロットの y と z。原点の列（x=0）は利用者の fixture が柱を建てることが多いので避ける
SLOT_Y = -60
SLOT_Z = -8.5
# フラットワールドの地表。アリーナはここから上を空気にし、下を地面として敷き直す
GROUND_Y = -60
# RCON の応答がこれで始まればコマンドは失敗している（ServerProcess.command は例外を投げない）。
# "No player was found" は、プレイヤーがサーバーから切断されている（プロセスは生きていても）ときの tp / clear 等の応答
ERROR_RESPONSE = re.compile(
    r"^(Unknown or incomplete command|Incorrect argument|Too many blocks|Cannot|That position is not loaded"
    r"|No player was found)",
    re.MULTILINE,
)
# 参加プレイヤーがサーバーに居ないときの応答。この応答を受けたプレイヤーは次のテストの前に起動し直す
PLAYER_MISSING = "No player was found"
# 失敗ではない応答。kill は殺すものが無くても、op/deop は既にその状態でもよい
KILL_IGNORE = ("No entity was found",)
OP_IGNORE = ("Nothing changed",)
# instant_health の回復量は 4 << amplifier。Java はシフト量を 5 ビットに丸めるため 255 では 4 << 31 = 0 になり何も回復しない。
# 10 なら 4096 HP で十分。saturation も同じ値にしておく（1 秒間、毎 tick 11 ずつ満腹度が戻る）
EFFECT_AMPLIFIER = 10


@dataclass(frozen=True)
class ResetCommand:
    """リセットのコンソールコマンド 1 つと、エラーとみなさない応答の断片。

    player は、そのコマンドが対象にする参加プレイヤー（tp / clear / op など）。
    "No player was found" の応答をそのプレイヤーの切断として扱うために持つ。
    """

    command: str
    ignore: tuple[str, ...] = ()
    player: str | None = None

    def is_error(self, response: str) -> bool:
        """応答がエラーの形で、かつ無視してよい応答でもないか。"""
        if any(fragment in response for fragment in self.ignore):
            return False
        return ERROR_RESPONSE.search(response) is not None


def default_slot(index: int) -> str:
    """spawn: に無いプレイヤーの tp 先。x を 2 ブロックずつずらして横に並べる。"""
    return f"{0.5 + 2 * index:g} {SLOT_Y} {SLOT_Z:g} 0 0"


def spawn_of(name: str, index: int, reset_spec: ResetSpec) -> str:
    """スイートの spawn: があればそれ、無ければスロット index の既定位置。"""
    return reset_spec.spawn.get(name) or default_slot(index)


def spawnpoint_of(spawn: str) -> str:
    """spawnpoint コマンドは角度を 1 つ（yaw）しか取らないため、座標の 3 つだけを使う。"""
    return " ".join(spawn.split()[:3])


def reset_commands(test: TestSpec, joined: list[str], reset_spec: ResetSpec) -> list[ResetCommand]:
    """テストの前にサーバーへ送るコマンドを、実行すべき順序で返す。

    先に参加プレイヤーを退避してからアリーナを fill する。逆にすると足場が消えて落下し、
    減った体力が以後のスクリーンショットに残る。joined に無いプレイヤーは参加していないので触らない。
    """
    participants = [player for player in test.players if player.name in joined]
    parked = [name for name in joined if name not in {player.name for player in participants}]
    # スロット番号は参加順（和集合の順）で固定し、テストが変わっても同じプレイヤーが同じ場所に立つようにする
    spawns = {
        player.name: spawn_of(player.name, joined.index(player.name), reset_spec) for player in participants
    }
    commands: list[ResetCommand] = []

    # 1. 参加プレイヤーを退避する
    for player in participants:
        commands.append(ResetCommand(f"gamemode {reset_spec.gamemode} {player.name}", player=player.name))
        commands.append(ResetCommand(f"tp {player.name} {spawns[player.name]}", player=player.name))
    # 2. 参加しないプレイヤーは駐機場所へ。サーバーから切断されていれば画面に出ないので、駐機は失敗にしない
    #    （切断は GameSession.reset が覚えておき、次に参加するテストの前に起動し直す）
    for name in parked:
        commands.append(ResetCommand(f"tp {name} {PARKING_SPOT}", ignore=(PLAYER_MISSING,), player=name))
    # 3. アリーナを空気にし、地面を敷き直し、残ったエンティティを消す
    if reset_spec.arena is not False:
        commands.extend(arena_commands(reset_spec.arena.size, reset_spec.arena.height))
    # 4. ワールドの時刻と天候
    commands.append(ResetCommand("time set noon"))
    commands.append(ResetCommand("weather clear"))
    # 5. 参加プレイヤーの状態（インベントリ・効果・体力・経験値・タイトル・リスポーン地点・OP）。
    #    effect clear は give より前に置く。後に置くと saturation が最初の tick を迎える前に消される
    for player in participants:
        name = player.name
        commands.extend(
            [
                ResetCommand(f"clear {name}", player=name),
                ResetCommand(f"effect clear {name}", player=name),
                ResetCommand(f"effect give {name} minecraft:instant_health 1 {EFFECT_AMPLIFIER} true", player=name),
                ResetCommand(f"effect give {name} minecraft:saturation 1 {EFFECT_AMPLIFIER} true", player=name),
                ResetCommand(f"experience set {name} 0 points", player=name),
                ResetCommand(f"experience set {name} 0 levels", player=name),
                ResetCommand(f"title {name} clear", player=name),
                ResetCommand(f"spawnpoint {name} {spawnpoint_of(spawns[name])}", player=name),
                ResetCommand(f"{'op' if player.op else 'deop'} {name}", ignore=OP_IGNORE, player=name),
            ]
        )
    return commands


def arena_commands(size: int, height: int) -> list[ResetCommand]:
    """原点を中心とした size×size、地表から height ブロックの範囲を空気にし、地面を 4 層で敷き直す。"""
    low = -(size // 2)
    high = low + size - 1
    top = GROUND_Y + height - 1

    def fill(y_from: int, y_to: int, block: str) -> ResetCommand:
        return ResetCommand(f"fill {low} {y_from} {low} {high} {y_to} {high} {block}")

    return [
        fill(GROUND_Y, top, "minecraft:air"),
        fill(GROUND_Y - 1, GROUND_Y - 1, "minecraft:grass_block"),
        fill(GROUND_Y - 3, GROUND_Y - 2, "minecraft:dirt"),
        fill(GROUND_Y - 4, GROUND_Y - 4, "minecraft:bedrock"),
        ResetCommand("kill @e[type=!player]", ignore=KILL_IGNORE),
    ]
