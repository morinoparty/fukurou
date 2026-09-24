"""scenario パッケージの主要な分岐を確認するテスト。"""

import hashlib
from pathlib import Path

import pytest

from fukurou.scenario import (
    Chat,
    PlayerSpec,
    PressKey,
    ScenarioError,
    ScenarioSource,
    Screenshot,
    ServerCommand,
    ServerWaitForLog,
    Wait,
    load_scenario,
    parse_scenario,
)

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
