"""scenario パッケージの主要な分岐を確認するテスト。"""

import hashlib
from pathlib import Path

import pytest

from fukurou.scenario import (
    Chat,
    Parallel,
    ParallelPosition,
    PlayerSpec,
    PressKey,
    Repeat,
    ScenarioError,
    ScenarioSource,
    Screenshot,
    ServerCommand,
    ServerWaitForLog,
    Wait,
    load_scenario,
    parse_scenario,
)
from fukurou.scenario.model import expand_steps

EXAMPLES_DIR = Path(__file__).resolve().parents[1] / "examples"


def scenario(*steps, players=({"name": "Alice"},)):
    """テスト用に、プレイヤー定義とステップからシナリオの JSON 相当の値を作る。"""
    return {"players": list(players), "steps": list(steps)}


def test_routes_steps_by_target():
    parsed = parse_scenario(
        scenario(
            {"on": "server", "action": "command", "command": "/time set noon"},
            {"on": "server", "action": "wait_for_log", "pattern": "Done"},
            {"on": "Alice", "action": "press_key", "key": "Enter"},
            {"on": "Bob", "action": "chat", "text": "/say hello"},
            {"on": "Bob", "action": "screenshot", "name": "greeting"},
            {"action": "wait", "seconds": 1},
            players=({"name": "Alice", "op": True}, {"name": "Bob"}),
        )
    )
    assert parsed.players == [PlayerSpec(name="Alice", op=True), PlayerSpec(name="Bob")]
    assert parsed.steps == [
        ServerCommand(on="server", command="time set noon"),
        ServerWaitForLog(on="server", pattern="Done", timeout=60.0),
        PressKey(on="Alice", key="Return"),
        Chat(on="Bob", text="/say hello"),
        Screenshot(on="Bob", name="greeting"),
        Wait(seconds=1),
    ]


def test_rejects_actions_for_the_wrong_target_and_typos():
    with pytest.raises(ScenarioError, match="does not match any of the expected tags"):
        parse_scenario(scenario({"on": "server", "action": "press_key", "key": "t"}))
    with pytest.raises(ScenarioError, match="does not match any of the expected tags"):
        parse_scenario(scenario({"on": "Alice", "action": "command", "command": "stop"}))
    with pytest.raises(ScenarioError, match="Extra inputs are not permitted"):
        parse_scenario(scenario({"on": "Alice", "action": "press_key", "key": "t", "key_inturrupt": "t"}))


def test_rejects_undeclared_or_invalid_players():
    with pytest.raises(ScenarioError, match="not declared in players"):
        parse_scenario(scenario({"on": "Carol", "action": "chat", "text": "hi"}))
    with pytest.raises(ScenarioError, match="reserved"):
        parse_scenario(scenario({"action": "wait", "seconds": 1}, players=({"name": "server"},)))
    with pytest.raises(ScenarioError, match="unique"):
        parse_scenario(scenario({"action": "wait", "seconds": 1}, players=({"name": "Alice"}, {"name": "Alice"})))


def test_rejects_invalid_values():
    with pytest.raises(ScenarioError, match="greater than 0"):
        parse_scenario(scenario({"action": "wait", "seconds": 0}))
    with pytest.raises(ScenarioError, match="invalid regular expression"):
        parse_scenario(scenario({"on": "server", "action": "wait_for_log", "pattern": "("}))
    with pytest.raises(ScenarioError, match="should match pattern"):
        parse_scenario(scenario({"on": "Alice", "action": "screenshot", "name": "../escape"}))
    with pytest.raises(ScenarioError, match="duplicate"):
        parse_scenario(
            scenario(
                {"on": "Alice", "action": "screenshot", "name": "a"},
                {"on": "Alice", "action": "screenshot", "name": "a"},
            )
        )
    # 失敗時の画面の名前と衝突しないよう予約している
    with pytest.raises(ScenarioError, match="reserved for the failure screenshot"):
        parse_scenario(scenario({"on": "Alice", "action": "screenshot", "name": "failure"}))


def test_yaml_and_json_are_both_accepted():
    yaml_text = """
players:
  - name: Alice
steps:
  - {on: server, action: command, command: time set noon}
  - action: wait
    seconds: 2
"""
    # タブでインデントした JSON は YAML としては読めないが、JSON としては読める
    json_text = '{\n\t"players": [{"name": "Alice"}],\n\t"steps": [{"action": "wait", "seconds": 2}]\n}'
    assert ScenarioSource.inline(yaml_text).parse().steps[1] == Wait(seconds=2)
    assert ScenarioSource.inline(json_text).parse().steps == [Wait(seconds=2)]
    with pytest.raises(ScenarioError, match="neither valid JSON nor valid YAML"):
        ScenarioSource.inline("players: [").parse()


def test_source_records_name_origin_and_hash(tmp_path):
    path = tmp_path / "hello-world.yml"
    text = "players: [{name: Alice}]\nsteps: [{action: wait, seconds: 1}]\n"
    path.write_text(text, encoding="utf-8")
    source = ScenarioSource.from_file(path)
    assert source.name == "hello-world"
    assert source.source == f"file:{path.as_posix()}"
    assert source.sha256 == hashlib.sha256(text.encode()).hexdigest()
    assert ScenarioSource.inline(text).name == "inline"


def test_bundled_examples_are_valid():
    # examples/ のスイートファイルは慣習で "fukurou.yml"（README・docs/usage.md 参照）。
    # シナリオはトップレベルの単発の例と examples/scenarios/ 配下にある
    paths = (
        sorted(EXAMPLES_DIR.glob("*.json"))
        + sorted(path for path in EXAMPLES_DIR.glob("*.y*ml") if path.name != "fukurou.yml")
        + sorted(EXAMPLES_DIR.glob("scenarios/*.json"))
        + sorted(EXAMPLES_DIR.glob("scenarios/*.y*ml"))
    )
    assert paths
    for path in paths:
        assert load_scenario(path).steps, path.name


def test_yaml_on_is_not_a_boolean():
    # YAML 1.1 では on / yes が真偽値になるが、シナリオでは文字列として読む
    parsed = ScenarioSource.inline(
        "players: [{name: Alice, op: true}]\nsteps:\n  - on: Alice\n    action: chat\n    text: yes\n"
    ).parse()
    assert parsed.steps == [Chat(on="Alice", text="yes")]
    assert parsed.players[0].op is True


def test_test_metadata_has_defaults_and_players_are_optional():
    parsed = parse_scenario({"steps": [{"on": "Alice", "action": "chat", "text": "hi"}]})
    # players を省略した場合、on の検証はスイートの players が決まる discovery で行う
    assert parsed.players is None
    assert (parsed.name, parsed.tags, parsed.isolation, parsed.timeout, parsed.versions, parsed.use) == (
        None, [], None, 600.0, None, []
    )
    full = parse_scenario(
        scenario({"action": "wait", "seconds": 1})
        | {"name": "Wait", "tags": ["slow"], "isolation": "fresh-server", "timeout": 30, "versions": "1.21.6-1.21.11",
           "use": ["arena"]}
    )
    assert (full.name, full.tags, full.isolation, full.timeout, full.versions, full.use) == (
        "Wait", ["slow"], "fresh-server", 30.0, "1.21.6-1.21.11", ["arena"]
    )


def test_rejects_invalid_test_metadata():
    base = scenario({"action": "wait", "seconds": 1})
    with pytest.raises(ScenarioError, match="'latest' is not allowed"):
        parse_scenario(base | {"versions": "latest"})
    with pytest.raises(ScenarioError, match="Input should be 'reset' or 'fresh-server'"):
        parse_scenario(base | {"isolation": "shared"})
    with pytest.raises(ScenarioError, match="should match pattern"):
        parse_scenario(base | {"use": ["../arena"]})
    with pytest.raises(ScenarioError, match="at least 1 item"):
        parse_scenario(base | {"players": []})


# --- parallel / repeat / 複数プレイヤーの on ------------------------------------------------


def expanded(*steps, players=({"name": "Alice"}, {"name": "Bob"})):
    """シナリオを検証し、展開後のステップ（ExpandedStep）の一覧を返す。"""
    return expand_steps(parse_scenario(scenario(*steps, players=players)).steps)


def test_blocks_parse_and_a_single_player_list_is_a_plain_name():
    parsed = parse_scenario(
        scenario(
            {"action": "parallel", "steps": [{"on": ["Alice"], "action": "chat", "text": "hi"}]},
            {"action": "repeat", "times": 2, "as": "n", "steps": [{"action": "wait", "seconds": 1}]},
        )
    )
    assert parsed.steps == [
        Parallel(steps=[Chat(on="Alice", text="hi")]),
        Repeat(times=2, as_="n", steps=[Wait(seconds=1)]),
    ]
    with pytest.raises(ScenarioError, match="must be unique"):
        parse_scenario(scenario({"on": ["Alice", "Alice"], "action": "chat", "text": "hi"}))
    # ブロックは on を持たない
    with pytest.raises(ScenarioError, match="does not match any of the expected tags"):
        parse_scenario(scenario({"on": "Alice", "action": "parallel", "steps": [{"action": "wait", "seconds": 1}]}))


def test_repeat_unrolls_with_placeholders_and_positions():
    steps = expanded(
        {
            "action": "repeat",
            "times": 2,
            "steps": [
                {"on": "Alice", "action": "screenshot", "name": "shot-${i}"},
                {"action": "repeat", "times": 2, "as": "j", "steps": [{"on": "Bob", "action": "press_key", "key": "${j0}"}]},
            ],
        }
    )
    assert [step.step for step in steps] == [
        Screenshot(on="Alice", name="shot-1"),
        PressKey(on="Bob", key="0"),
        PressKey(on="Bob", key="1"),
        Screenshot(on="Alice", name="shot-2"),
        PressKey(on="Bob", key="0"),
        PressKey(on="Bob", key="1"),
    ]
    # 内側の repeat は外側の繰り返しごとに別のブロック番号になる
    assert [tuple((r.block, r.iteration, r.of) for r in step.repeat) for step in steps] == [
        ((0, 1, 2),),
        ((0, 1, 2), (1, 1, 2)),
        ((0, 1, 2), (1, 2, 2)),
        ((0, 2, 2),),
        ((0, 2, 2), (2, 1, 2)),
        ((0, 2, 2), (2, 2, 2)),
    ]
    # 置き換えた後の値にもエイリアスが効く（"Enter" → "Return"）
    [step] = expanded({"action": "repeat", "times": 1, "steps": [{"on": "Alice", "action": "press_key", "key": "${i}"}]})
    assert step.step == PressKey(on="Alice", key="1")


def test_multi_player_on_becomes_parallel_lanes():
    steps = expanded(
        {"on": ["Alice", "Bob"], "action": "screenshot", "name": "view"},
        {
            "action": "parallel",
            "steps": [
                {"on": ["Alice", "Bob"], "action": "chat", "text": "hi"},
                {"on": "server", "action": "wait_for_log", "pattern": "hi"},
            ],
        },
    )
    assert [(step.step.on, step.parallel) for step in steps] == [
        ("Alice", ParallelPosition(0, 0)),
        ("Bob", ParallelPosition(0, 1)),
        # parallel の直接の子の複数プレイヤーの on は、入れ子にせずレーンに広げる
        ("Alice", ParallelPosition(1, 0)),
        ("Bob", ParallelPosition(1, 1)),
        ("server", ParallelPosition(1, 2)),
    ]


@pytest.mark.parametrize(
    ("step", "message"),
    [
        (
            {"action": "parallel", "steps": [{"action": "parallel", "steps": [{"action": "wait", "seconds": 1}]}]},
            "cannot be nested",
        ),
        (
            {"action": "parallel", "steps": [{"on": "Alice", "action": "chat", "text": "a"}, {"on": "Alice", "action": "press_key", "key": "t"}]},
            "parallel children 0 and 1 both send client input .* to Alice",
        ),
        (
            {"action": "parallel", "steps": [{"on": "server", "action": "command", "command": "say"}] * 17},
            "at most 16 children at once, got 17",
        ),
        (
            {"action": "repeat", "times": 2, "steps": [{"action": "repeat", "times": 2, "as": "i0", "steps": [{"action": "wait", "seconds": 1}]}]},
            "repeat variable 'i0' clashes with an outer repeat",
        ),
        (
            {"action": "repeat", "times": 2, "steps": [{"on": "Alice", "action": "chat", "text": "${n}"}]},
            r"step 0 > step 0: text: unknown placeholder '\$\{n\}'",
        ),
        ({"action": "repeat", "times": 101, "steps": [{"action": "wait", "seconds": 1}]}, "less than or equal to 100"),
        ({"action": "repeat", "times": 2, "as": "I", "steps": [{"action": "wait", "seconds": 1}]}, "should match pattern"),
        (
            {"action": "repeat", "times": 2, "steps": [{"on": "Alice", "action": "screenshot", "name": "same"}]},
            "duplicate screenshot names: Alice/same",
        ),
        ({"on": "Alice", "action": "screenshot", "name": "shot-${i}"}, "only work inside a repeat"),
        # 撮影の名前の誤った変数名も、他のフィールドと同じ "unknown placeholder" で報告する
        (
            {"action": "repeat", "times": 2, "steps": [{"on": "Alice", "action": "screenshot", "name": "s${I}"}]},
            r"step 0 > step 0: name: unknown placeholder '\$\{I\}' \(available: \$\{i\}, \$\{i0\}\)",
        ),
        # 複数プレイヤーの on で広げたレーンがあっても、衝突は利用者が書いた子の番号で報告する
        (
            {"action": "parallel", "steps": [{"on": ["Alice", "Bob"], "action": "screenshot", "name": "s"}, {"on": "Bob", "action": "chat", "text": "a"}]},
            "parallel children 0 and 1 both send client input .* to Bob",
        ),
        ({"on": "server", "action": "wait_for_log", "pattern": "x${2}"}, r"pattern: unknown placeholder '\$\{2\}'"),
        (
            {"action": "repeat", "times": 100, "steps": [{"action": "repeat", "times": 11, "as": "j", "steps": [{"action": "wait", "seconds": 1}]}]},
            "at most 1000 planned steps",
        ),
    ],
)
def test_block_rules_are_validation_errors(step, message):
    with pytest.raises(ScenarioError, match=message):
        parse_scenario(scenario(step, players=({"name": "Alice"}, {"name": "Bob"})))


def test_parallel_allows_shared_server_actions_and_log_waits():
    steps = expanded(
        {
            "action": "parallel",
            "steps": [
                {"on": "Alice", "action": "chat", "text": "hi"},
                {"on": "Alice", "action": "wait_for_log", "pattern": "hi"},
                {"on": "server", "action": "wait_for_log", "pattern": "hi"},
                {"on": "server", "action": "assert_no_log", "pattern": "error"},
                {"action": "wait", "seconds": 1},
            ],
        }
    )
    assert [step.parallel.lane for step in steps] == [0, 1, 2, 3, 4]
