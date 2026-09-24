"""テストの前のリセットのコマンド列（純粋関数）のテスト。"""

from fukurou.run.isolation import (
    ERROR_RESPONSE,
    PARKING_SPOT,
    ResetCommand,
    default_slot,
    reset_commands,
)
from fukurou.scenario import ArenaSpec, PlayerSpec, ResetSpec, TestSpec


def spec(*players: PlayerSpec) -> TestSpec:
    return TestSpec(
        id="t", name="t", order=0, source="inline", sha256="0" * 64, tags=[], isolation="reset",
        timeout=600, versions=None, players=list(players), steps=[],
    )


ALICE = PlayerSpec(name="Alice", op=True)
BOB = PlayerSpec(name="Bob")


def commands(test: TestSpec, joined: list[str], reset_spec: ResetSpec = ResetSpec()) -> list[str]:
    return [command.command for command in reset_commands(test, joined, reset_spec)]


def test_reset_evacuates_players_before_filling_the_arena():
    result = commands(spec(ALICE, BOB), ["Alice", "Bob"])
    # 退避（gamemode → tp）→ アリーナ → 時刻と天候 → プレイヤーの状態 の順
    assert result[:4] == [
        "gamemode survival Alice",
        "tp Alice 0.5 -60 -8.5 0 0",
        "gamemode survival Bob",
        "tp Bob 2.5 -60 -8.5 0 0",
    ]
    assert result[4:9] == [
        "fill -16 -60 -16 15 -37 15 minecraft:air",
        "fill -16 -61 -16 15 -61 15 minecraft:grass_block",
        "fill -16 -63 -16 15 -62 15 minecraft:dirt",
        "fill -16 -64 -16 15 -64 15 minecraft:bedrock",
        "kill @e[type=!player]",
    ]
    assert result[9:11] == ["time set noon", "weather clear"]
    alice = result[11:20]
    # effect clear は give より前（後だと saturation が tick する前に消える）。amplifier は 4 << n が 0 にならない値
    assert alice == [
        "clear Alice",
        "effect clear Alice",
        "effect give Alice minecraft:instant_health 1 10 true",
        "effect give Alice minecraft:saturation 1 10 true",
        "experience set Alice 0 points",
        "experience set Alice 0 levels",
        "title Alice clear",
        "spawnpoint Alice 0.5 -60 -8.5",
        "op Alice",
    ]
    assert result[-1] == "deop Bob"
    assert len(result) == 29
    # プレイヤー向けのコマンドは対象を持ち、ワールド向けのコマンドは持たない
    players = [command.player for command in reset_commands(spec(ALICE, BOB), ["Alice", "Bob"], ResetSpec())]
    assert players[:4] == ["Alice", "Alice", "Bob", "Bob"] and players[4:11] == [None] * 7
    assert players[11:20] == ["Alice"] * 9 and players[20:] == ["Bob"] * 9


def test_parked_players_and_custom_spawns():
    reset_spec = ResetSpec(spawn={"Bob": "0.5 -60 8.5 180 -15"}, gamemode="creative")
    result = commands(spec(BOB), ["Alice", "Bob"], reset_spec)
    # Bob だけが参加するので、Alice は駐機場所へ。Bob のスロットは和集合での位置（1）ではなく spawn: の値
    assert result[:3] == ["gamemode creative Bob", "tp Bob 0.5 -60 8.5 180 -15", f"tp Alice {PARKING_SPOT}"]
    parking = reset_commands(spec(BOB), ["Alice", "Bob"], reset_spec)[2]
    # 駐機は切断されたプレイヤーでも失敗にしない（対象は覚えておく）
    assert parking.player == "Alice" and not parking.is_error("No player was found")
    # spawnpoint は角度を 1 つしか取らないため座標だけ
    assert "spawnpoint Bob 0.5 -60 8.5" in result
    assert "deop Bob" in result and not any("Alice" in line for line in result[3:])


def test_players_not_joined_are_not_touched_and_arena_can_be_disabled():
    reset_spec = ResetSpec(arena=False)
    result = commands(spec(ALICE, BOB), ["Alice"], reset_spec)
    assert not any("Bob" in line for line in result)
    assert not any(line.startswith(("fill", "kill")) for line in result)
    assert result[2:4] == ["time set noon", "weather clear"]


def test_arena_size_follows_the_spec():
    reset_spec = ResetSpec(arena=ArenaSpec(size=16, height=8))
    result = commands(spec(ALICE), ["Alice"], reset_spec)
    assert "fill -8 -60 -8 7 -53 7 minecraft:air" in result


def test_default_slots_avoid_the_origin_column():
    assert default_slot(0) == "0.5 -60 -8.5 0 0"
    assert default_slot(3) == "6.5 -60 -8.5 0 0"


def test_error_responses():
    assert ResetCommand("fill 0 0 0 1 1 1 minecraft:air").is_error("Too many blocks in the specified area (32769 > 32768)")
    assert ResetCommand("tp Alice 0 0 0").is_error("Unknown or incomplete command, see below for error\ntp Alice<--[HERE]")
    assert ResetCommand("spawnpoint Alice 0 0 0 0 0").is_error("Incorrect argument for command")
    assert not ResetCommand("fill 0 0 0 1 1 1 minecraft:air").is_error("Successfully filled 8 blocks")
    # kill と op/deop の「何も無かった」は失敗ではない
    assert not ResetCommand("kill @e[type=!player]", ignore=("No entity was found",)).is_error("No entity was found")
    assert not ResetCommand("op Alice", ignore=("Nothing changed",)).is_error("Nothing changed. The player already is an operator")
    # instant 効果を消した後の effect clear の応答も失敗ではない
    assert not ResetCommand("effect clear Alice").is_error("Target either has no effects to remove, or has effects that cannot be removed")
    # 無視の指定が無ければエラーの形の応答はエラー
    assert ResetCommand("kill @e[type=!player]").is_error("Cannot kill that entity")
    assert ERROR_RESPONSE.search("Teleported Alice to 0.5, -60.0, -8.5") is None
    # サーバーから切断されたプレイヤーへの tp / gamemode はこの応答になる（プロセスが生きていても）
    assert ResetCommand("tp Alice 0.5 -60 -8.5 0 0", player="Alice").is_error("No player was found")
    assert ResetCommand("gamemode survival Alice", player="Alice").is_error("No player was found")
