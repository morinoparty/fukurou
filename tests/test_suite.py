"""スイートファイルのモデルと、選択からテストの一覧を作る discover_tests のテスト。"""

import hashlib
import json
from pathlib import Path

import pytest

from fukurou.scenario import (
    ArenaSpec,
    Chat,
    ParallelPosition,
    PlayerSpec,
    RepeatPosition,
    ResetSpec,
    ScenarioError,
    Selection,
    Suite,
    SuiteError,
    Wait,
    discover_tests,
    inspect_tests,
    parse_document,
)
from fukurou.scenario.suite import MAX_ARENA_BLOCKS

WAIT = {"action": "wait", "seconds": 1}


def write(path: Path, data) -> Path:
    """JSON の値（または YAML の文字列）をファイルに書き、そのパスを返す。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data if isinstance(data, str) else json.dumps(data), encoding="utf-8")
    return path


def make_suite(tmp_path: Path, **fields) -> Path:
    """game-test/fukurou.yml に Alice と Bob を既定のプレイヤーとするスイートを書く。"""
    data = {"scenarios": ["scenarios/*.json"], "players": [{"name": "Alice", "op": True}, {"name": "Bob"}]} | fields
    return write(tmp_path / "game-test" / "fukurou.yml", data)


def make_test(tmp_path: Path, test_id: str, **fields) -> Path:
    return write(tmp_path / "game-test" / "scenarios" / f"{test_id}.json", {"steps": [WAIT]} | fields)


def ids(tests) -> list[str]:
    return [test.id for test in tests]


# --- Suite のモデル ------------------------------------------------------------------


def test_suite_defaults_and_reset_spec():
    suite = Suite.model_validate(parse_document("players: [{name: Alice}]\nbeforeEach: [{action: wait, seconds: 1}]"))
    assert suite.isolation == "reset"
    assert suite.arena == ArenaSpec(size=32, height=24)
    assert suite.before_each == [Wait(seconds=1)]
    assert suite.reset_spec == ResetSpec(arena=ArenaSpec(), gamemode="survival", spawn={}, settle=2.0)
    # スイートが無くても同じ既定値でリセットする
    assert ResetSpec.of(None) == suite.reset_spec
    assert Suite.model_validate({"arena": False, "settle": 3}).reset_spec.arena is False


def test_suite_rejects_invalid_values():
    # gamerule を変えずに 1 回の fill で埋められる大きさに限る
    assert ArenaSpec(size=32, height=32).size * 32 * 32 == MAX_ARENA_BLOCKS
    with pytest.raises(ValueError, match="has 33792 blocks .* must be at most 32768"):
        Suite.model_validate({"arena": {"size": 32, "height": 33}})
    with pytest.raises(ValueError, match="expected 'x y z' or 'x y z yaw pitch'"):
        Suite.model_validate({"spawn": {"Alice": "0 -60"}})
    with pytest.raises(ValueError, match="fixture 'shots': screenshot name 'failure' is reserved"):
        Suite.model_validate({"fixtures": {"shots": [{"on": "Alice", "action": "screenshot", "name": "failure"}]}})
    with pytest.raises(ValueError, match="Extra inputs are not permitted"):
        Suite.model_validate({"fixture": {}})


# --- discover_tests: 発見と id -------------------------------------------------------


def test_globs_are_relative_to_the_suite_sorted_and_deduplicated(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(tmp_path, scenarios=["scenarios/b-*.json", "scenarios/*.json"])
    b = make_test(tmp_path, "b-second")
    make_test(tmp_path, "a-first")
    make_test(tmp_path, "c-third")
    # 同じファイルが glob・--scenarios・--scenario-file の複数に一致しても 1 回だけ
    selection = Selection(
        suite=Path("game-test/fukurou.yml"),
        scenario_globs=["game-test/scenarios/a-*.json"],
        scenario_files=[b.resolve()],
    )
    suite, tests = discover_tests(selection)
    assert ids(tests) == ["b-second", "a-first", "c-third"]
    assert [test.order for test in tests] == [0, 1, 2]
    assert tests[0].source == "file:game-test/scenarios/b-second.json"
    assert tests[0].sha256 == hashlib.sha256(b.read_bytes()).hexdigest()
    assert suite is not None and suite.source == "file:game-test/fukurou.yml"


def test_duplicate_or_invalid_ids_are_errors(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    # 一致しない glob（missing/*.json）はエラーにしない
    make_suite(tmp_path, scenarios=["scenarios/*.json", "other/*.json", "missing/*.json"])
    make_test(tmp_path, "same")
    write(tmp_path / "game-test" / "other" / "same.json", {"steps": [WAIT]})
    write(tmp_path / "game-test" / "other" / "-bad.json", {"steps": [WAIT]})
    with pytest.raises(SuiteError) as error:
        discover_tests(Selection(suite=Path("game-test/fukurou.yml")))
    message = str(error.value)
    assert "same: duplicate test id (from file:game-test/scenarios/same.json, file:game-test/other/same.json)" in message
    assert "the test id '-bad'" in message
    assert len(message.splitlines()) == 2


def test_inline_scenario_needs_players_without_a_suite():
    _, tests = discover_tests(Selection(scenario_text='{"players": [{"name": "Alice"}], "steps": [{"action": "wait", "seconds": 1}]}'))
    assert (tests[0].id, tests[0].name, tests[0].source, tests[0].isolation) == ("inline", "inline", "inline", "reset")
    with pytest.raises(SuiteError, match="no players: declare players"):
        discover_tests(Selection(scenario_text="steps: [{action: wait, seconds: 1}]"))


def test_no_source_or_an_empty_selection_is_an_error(tmp_path, monkeypatch):
    with pytest.raises(SuiteError, match="select tests with --suite"):
        discover_tests(Selection(test_filters=["x"]))
    monkeypatch.chdir(tmp_path)
    make_suite(tmp_path)
    make_test(tmp_path, "only")
    with pytest.raises(SuiteError, match=r"no tests were selected \(filters: --test typo\)"):
        discover_tests(Selection(suite=Path("game-test/fukurou.yml"), test_filters=["typo"]))


def test_an_invalid_suite_or_scenario_is_reported(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    with pytest.raises(SuiteError, match="could not read the suite file"):
        discover_tests(Selection(suite=Path("missing.yml")))
    write(tmp_path / "bad.yml", "players: [")
    with pytest.raises(SuiteError, match="the suite is neither valid JSON nor valid YAML"):
        discover_tests(Selection(suite=Path("bad.yml")))
    make_suite(tmp_path)
    make_test(tmp_path, "good")
    make_test(tmp_path, "broken", steps=[])
    # 問題があっても、有効なテストは validate で表示できるよう残す
    discovery = inspect_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert ids(discovery.tests) == ["good"]
    assert len(discovery.errors) == 1 and discovery.errors[0].startswith("broken: invalid scenario:")


# --- discover_tests: fixture とプレイヤー ------------------------------------------------


def test_steps_expand_before_each_then_fixtures_then_the_test(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(
        tmp_path,
        beforeEach=[{"on": "server", "action": "command", "command": "say hi"}],
        fixtures={
            "arena": [{"action": "wait", "seconds": 2}],
            "front-view": [{"on": "Alice", "action": "press_key", "key": "F5"}],
        },
    )
    make_test(tmp_path, "stamp", use=["front-view", "arena"], steps=[{"on": "Bob", "action": "chat", "text": "/st"}])
    _, [test] = discover_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert [(step.phase, step.fixture) for step in test.steps] == [
        ("beforeEach", None),
        ("fixture", "front-view"),
        ("fixture", "arena"),
        ("test", None),
    ]
    assert test.steps[-1].step == Chat(on="Bob", text="/st")
    assert all(step.skip_reason is None for step in test.steps)
    assert test.players == [PlayerSpec(name="Alice", op=True), PlayerSpec(name="Bob")]


def test_unknown_fixtures_and_players_are_errors(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(tmp_path, fixtures={"typo": [{"on": "Alise", "action": "chat", "text": "hi"}]})
    make_test(tmp_path, "uses-missing", use=["missing"])
    make_test(tmp_path, "wrong-player", steps=[{"on": "Carol", "action": "chat", "text": "hi"}])
    discovery = inspect_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert sorted(discovery.errors) == [
        "suite fixture 'typo' step 0: player 'Alise' is not declared in any players",
        "uses-missing: unknown fixture 'missing' in use (defined: typo)",
        "wrong-player: step 0: player 'Carol' is not declared in players",
    ]
    # スイートが無ければ fixture は使えない
    with pytest.raises(SuiteError, match="unknown fixture 'arena' in use \\(defined: none\\)"):
        discover_tests(Selection(scenario_text='{"players": [{"name": "Alice"}], "use": ["arena"], "steps": [{"action": "wait", "seconds": 1}]}'))


def test_shared_steps_for_players_outside_the_test_are_skipped(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(
        tmp_path,
        fixtures={"both": [{"on": "Alice", "action": "press_key", "key": "F5"}, {"on": "Bob", "action": "press_key", "key": "F5"}]},
    )
    # players を書いたテストはスイートの一覧を完全に置き換える（サブセットも可）
    make_test(tmp_path, "alice-only", players=[{"name": "Alice"}], use=["both"])
    _, [test] = discover_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert test.players == [PlayerSpec(name="Alice")]
    assert [step.skip_reason for step in test.steps] == [None, "player Bob is not in this test", None]


def test_screenshot_names_must_not_collide_across_fixtures_and_the_test(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    shot = {"on": "Alice", "action": "screenshot", "name": "view"}
    make_suite(tmp_path, fixtures={"shot": [shot]})
    make_test(tmp_path, "twice", use=["shot"], steps=[shot])
    with pytest.raises(SuiteError, match="twice: duplicate screenshot names: Alice/view"):
        discover_tests(Selection(suite=Path("game-test/fukurou.yml")))


# --- discover_tests: 順序・フィルタ・versions ------------------------------------------------


def test_reset_tests_run_before_fresh_server_tests_in_declared_order(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(tmp_path)
    make_test(tmp_path, "a", isolation="fresh-server")
    make_test(tmp_path, "b")
    make_test(tmp_path, "c", isolation="fresh-server")
    make_test(tmp_path, "d", isolation="reset")
    _, tests = discover_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert [(test.id, test.order, test.isolation) for test in tests] == [
        ("b", 0, "reset"),
        ("d", 1, "reset"),
        ("a", 2, "fresh-server"),
        ("c", 3, "fresh-server"),
    ]
    # --isolation は全テストを上書きし、宣言順のまま並ぶ
    _, tests = discover_tests(Selection(suite=Path("game-test/fukurou.yml"), isolation="reset"))
    assert [(test.id, test.isolation) for test in tests] == [("a", "reset"), ("b", "reset"), ("c", "reset"), ("d", "reset")]


def test_test_and_tag_filters_are_or_within_and_and_between(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(tmp_path)
    make_test(tmp_path, "stamp-thinking", tags=["stamps"])
    make_test(tmp_path, "stamp-sleeping", tags=["stamps", "slow"])
    make_test(tmp_path, "menu", tags=["ui"])
    suite = Path("game-test/fukurou.yml")
    assert ids(discover_tests(Selection(suite=suite, test_filters=["stamp-*", "menu"]))[1]) == [
        "menu", "stamp-sleeping", "stamp-thinking"
    ]
    assert ids(discover_tests(Selection(suite=suite, tag_filters=["slow", "ui"]))[1]) == ["menu", "stamp-sleeping"]
    assert ids(discover_tests(Selection(suite=suite, test_filters=["stamp-*"], tag_filters=["slow"]))[1]) == [
        "stamp-sleeping"
    ]


def test_versions_constraints_give_a_skip_reason(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(tmp_path)
    make_test(tmp_path, "new-only", versions="1.21.9-")
    make_test(tmp_path, "always")
    _, tests = discover_tests(Selection(suite=Path("game-test/fukurou.yml")))
    releases = ["1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.6"]
    by_id = {test.id: test for test in tests}
    assert by_id["new-only"].skip_reason_for("1.21.6", releases) == "versions: 1.21.9- does not include 1.21.6"
    assert by_id["new-only"].skip_reason_for("1.21.11", releases) is None
    assert by_id["always"].skip_reason_for("1.21.6", releases) is None


def test_scenario_errors_are_not_suite_errors():
    # ScenarioError は単体のシナリオの検証用（ValueError）。discovery はそれを SuiteError（exit 2）に包む
    assert not issubclass(ScenarioError, SuiteError)


# --- parallel / repeat の展開 ---------------------------------------------------------


def test_blocks_in_shared_steps_are_numbered_per_test_and_skip_absent_players_per_child(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    make_suite(
        tmp_path,
        beforeEach=[{"on": ["Alice", "Bob"], "action": "press_key", "key": "F1"}],
        fixtures={
            "views": [
                {
                    "action": "parallel",
                    "steps": [
                        {"on": "Bob", "action": "screenshot", "name": "bob-view"},
                        {"action": "repeat", "times": 2, "steps": [{"on": "Alice", "action": "chat", "text": "hi ${i}"}]},
                    ],
                }
            ]
        },
    )
    make_test(
        tmp_path,
        "alice-only",
        players=[{"name": "Alice"}],
        use=["views"],
        steps=[{"on": ["Alice"], "action": "screenshot", "name": "done"}, {"on": "server", "action": "command", "command": "say"}],
    )
    _, [test] = discover_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert [(step.phase, step.step.on, step.skip_reason) for step in test.steps] == [
        ("beforeEach", "Alice", None),
        # 複数プレイヤーの on のうち、テストに居ないプレイヤーの分だけが飛ぶ
        ("beforeEach", "Bob", "player Bob is not in this test"),
        ("fixture", "Bob", "player Bob is not in this test"),
        ("fixture", "Alice", None),
        ("fixture", "Alice", None),
        # 1 人だけの一覧はブロックにならない
        ("test", "Alice", None),
        ("test", "server", None),
    ]
    # ブロック番号は beforeEach・fixture・テスト自身を通した通し番号
    assert [step.parallel for step in test.steps] == [
        ParallelPosition(0, 0),
        ParallelPosition(0, 1),
        ParallelPosition(1, 0),
        ParallelPosition(1, 1),
        ParallelPosition(1, 1),
        None,
        None,
    ]
    assert [step.repeat for step in test.steps][3:5] == [(RepeatPosition(0, 1, 2),), (RepeatPosition(0, 2, 2),)]
    assert test.steps[4].step == Chat(on="Alice", text="hi 2")


def test_block_rules_apply_to_the_suite_and_the_whole_plan(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    nested = [{"action": "parallel", "steps": [{"action": "parallel", "steps": [WAIT]}]}]
    with pytest.raises(ValueError, match="fixture 'bad': step 0: parallel blocks cannot be nested"):
        Suite.model_validate({"fixtures": {"bad": nested}})
    # 展開後のステップ数の上限は fixture とテスト自身のステップを合わせて数える
    make_suite(
        tmp_path,
        fixtures={
            "many": [{"action": "repeat", "times": 100, "steps": [{"on": ["Alice", "Alise"], "action": "chat", "text": "x"}]}]
        },
    )
    make_test(tmp_path, "big", use=["many"], steps=[{"action": "repeat", "times": 100, "steps": [WAIT] * 9}])
    discovery = inspect_tests(Selection(suite=Path("game-test/fukurou.yml")))
    assert discovery.errors == [
        "suite fixture 'many' step 0 > step 0: player 'Alise' is not declared in any players",
        "big: a test may have at most 1000 planned steps after expanding repeat blocks",
    ]
