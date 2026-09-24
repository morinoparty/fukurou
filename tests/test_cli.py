"""CLI の選択引数の解釈と、validate / list / schema / run（実行系は偽物に差し替え）のテスト。

実際にサーバーを起動しようとする run の経路のテストは、実行系の tests/test_run.py にある。
"""

import json
from pathlib import Path
import sys
import types

import pytest

from fukurou.cli import main, split_values
from fukurou.java import parse_java_major
from fukurou.mojang import MojangManifest
from fukurou.scenario import Selection
from fukurou.schema import SCHEMA_FILES, SCHEMA_NAMES, schema_json

ROOT = Path(__file__).resolve().parents[1]
SMOKE = ROOT / "examples" / "smoke.json"
WAIT = {"action": "wait", "seconds": 1}


@pytest.fixture
def suite_dir(tmp_path, monkeypatch) -> Path:
    """カレントディレクトリに 3 テストのスイート（game-test/fukurou.yml）を作る。"""
    monkeypatch.chdir(tmp_path)
    scenarios = tmp_path / "game-test" / "scenarios"
    scenarios.mkdir(parents=True)
    (tmp_path / "game-test" / "fukurou.yml").write_text(
        "scenarios: [scenarios/*.json]\nplayers: [{name: Alice, op: true}]\n", encoding="utf-8"
    )
    tests = {
        "fresh": {"isolation": "fresh-server", "steps": [WAIT]},
        "stamp-a": {"tags": ["stamps"], "steps": [WAIT]},
        "stamp-b": {"tags": ["stamps"], "versions": "1.21.9-", "steps": [WAIT]},
    }
    for test_id, data in tests.items():
        (scenarios / f"{test_id}.json").write_text(json.dumps(data), encoding="utf-8")
    return tmp_path


def test_validate_accepts_valid_scenarios(capsys):
    assert main(["validate", "--scenario-file", str(SMOKE)]) == 0
    assert "smoke: valid (players: 1, steps: 4" in capsys.readouterr().out
    inline = "players: [{name: Alice}]\nsteps: [{action: wait, seconds: 1}]"
    # v1 と同じく単体のファイルとインラインを組み合わせられる
    assert main(["validate", "--scenario", inline, "--scenario-file", str(SMOKE)]) == 0
    assert capsys.readouterr().out.splitlines()[0].startswith("smoke: valid")


def test_validate_rejects_invalid_scenarios(tmp_path, capsys):
    assert main(["validate", "--scenario", '{"players": [], "steps": []}']) == 2
    assert "inline: invalid scenario" in capsys.readouterr().err
    assert main(["validate", "--scenario-file", str(tmp_path / "missing.json")]) == 2
    assert "could not read the scenario file" in capsys.readouterr().err
    # 選択が 1 つも無いのは入力の誤り
    assert main(["validate"]) == 2
    assert "select tests with --suite" in capsys.readouterr().err


def test_validate_prints_one_line_per_test_and_reports_problems(suite_dir, capsys):
    assert main(["validate", "--suite", "game-test/fukurou.yml"]) == 0
    # テストごとの行の下に、展開後のステップが字下げして並ぶ
    lines = [line for line in capsys.readouterr().out.splitlines() if not line.startswith(" ")]
    assert [line.split(":")[0] for line in lines] == ["stamp-a", "stamp-b", "fresh"]
    (suite_dir / "game-test" / "scenarios" / "broken.json").write_text('{"steps": []}', encoding="utf-8")
    assert main(["validate", "--suite", "game-test/fukurou.yml"]) == 2
    captured = capsys.readouterr()
    assert len([line for line in captured.out.splitlines() if not line.startswith(" ")]) == 3
    assert "fukurou: broken: invalid scenario" in captured.err


def test_validate_prints_the_expanded_steps(capsys):
    inline = json.dumps(
        {
            "players": [{"name": "Alice"}, {"name": "Bob"}],
            "steps": [
                {"action": "repeat", "times": 2, "steps": [{"on": ["Alice", "Bob"], "action": "screenshot", "name": "s${i}"}]},
                WAIT,
            ],
        }
    )
    assert main(["validate", "--scenario", inline]) == 0
    assert capsys.readouterr().out.splitlines() == [
        "inline: valid (players: 2, steps: 5, planned steps: 5, isolation: reset)",
        "    0 test Alice screenshot 's1' [parallel 0 lane 0] [repeat 0 1/2]",
        "    1 test Bob screenshot 's1' [parallel 0 lane 1] [repeat 0 1/2]",
        "    2 test Alice screenshot 's2' [parallel 1 lane 0] [repeat 0 2/2]",
        "    3 test Bob screenshot 's2' [parallel 1 lane 1] [repeat 0 2/2]",
        "    4 test - wait '1s'",
    ]


def test_list_prints_ids_in_run_order(suite_dir, capsys):
    assert main(["list", "--suite", "game-test/fukurou.yml"]) == 0
    assert json.loads(capsys.readouterr().out) == ["stamp-a", "stamp-b", "fresh"]
    # アクションの入力のようにカンマ区切りで渡しても、繰り返し指定と同じに扱う
    assert main(["list", "--suite", "game-test/fukurou.yml", "--test", "fresh, stamp-b", "--isolation", "reset"]) == 0
    assert json.loads(capsys.readouterr().out) == ["fresh", "stamp-b"]
    assert main(["list", "--suite", "game-test/fukurou.yml", "--tag", "stamps", "--test", "*-b"]) == 0
    assert json.loads(capsys.readouterr().out) == ["stamp-b"]


def test_list_exits_2_on_an_empty_selection(suite_dir, capsys):
    assert main(["list", "--suite", "game-test/fukurou.yml", "--tag", "typo"]) == 2
    assert "no tests were selected (filters: --tag typo)" in capsys.readouterr().err


def test_list_reports_versions_skips(suite_dir, capsys, monkeypatch):
    manifest = MojangManifest.model_validate(
        {"versions": [{"id": v, "type": "release"} for v in ("1.21.11", "1.21.9", "1.21.6")]}
    )
    monkeypatch.setattr("fukurou.mojang.fetch_manifest", lambda: manifest)
    assert main(["list", "--suite", "game-test/fukurou.yml", "--minecraft-version", "1.21.6"]) == 0
    captured = capsys.readouterr()
    # skipped のテストも選択に含めたまま（ビューアに行が出る）で、理由は stderr に出す
    assert json.loads(captured.out) == ["stamp-a", "stamp-b", "fresh"]
    assert "stamp-b will be skipped: versions: 1.21.9- does not include 1.21.6" in captured.err
    assert main(["list", "--suite", "game-test/fukurou.yml", "--minecraft-version", "latest"]) == 2


def test_split_values_accepts_repeats_commas_and_newlines():
    assert split_values(["a, b", "c\n\nd", " "]) == ["a", "b", "c", "d"]
    assert split_values(None) == []
    # パスはカンマを含み得るので、--scenario-file は改行だけで区切る
    assert split_values(["x,y.json\nz.json"], commas=False) == ["x,y.json", "z.json"]


@pytest.mark.parametrize("name", SCHEMA_NAMES)
def test_schema_command_prints_the_committed_schema(name, capsys):
    """schema/*.json が fukurou schema の出力とずれていたら失敗する（更新は fukurou schema で生成し直す）。"""
    assert main(["schema", name]) == 0
    printed = capsys.readouterr().out
    assert printed == schema_json(name)
    assert json.loads(printed)["$schema"].startswith("https://json-schema.org/")
    committed = ROOT / "schema" / SCHEMA_FILES[name]
    if not committed.exists():
        pytest.skip(f"schema/{SCHEMA_FILES[name]} is not generated yet")
    assert printed == committed.read_text(encoding="utf-8")


def test_suite_schema_uses_the_file_field_names():
    schema = json.loads(schema_json("suite"))
    assert {"$schema", "scenarios", "players", "fixtures", "beforeEach", "arena", "spawn", "settle"} <= set(
        schema["properties"]
    )
    assert schema["additionalProperties"] is False


def test_run_builds_options_and_calls_the_suite_runner(tmp_path, monkeypatch):
    received = []

    class FakeSuiteRun:
        def __init__(self, options):
            received.append(options)

        def execute(self) -> int:
            return 1

    # 実行系を読み込まずに、CLI が作る RunOptions だけを確かめる
    monkeypatch.setitem(sys.modules, "fukurou.run.suite_run", types.SimpleNamespace(SuiteRun=FakeSuiteRun))
    monkeypatch.setattr("signal.signal", lambda *args: None)
    code = main(
        ["run", "--minecraft-version", "1.21.11", "--accept-eula", "--suite", "game-test/fukurou.yml",
         "--scenarios", "a/*.json,b/*.json", "--scenario-file", "x.json", "--scenario-file", "y.json",
         "--test", "stamp-*", "--tag", "stamps", "--isolation", "fresh-server", "--fail-fast",
         "--work-dir", str(tmp_path / "work")]
    )
    assert code == 1
    [options] = received
    assert options.selection == Selection(
        suite=Path("game-test/fukurou.yml"),
        scenario_files=[Path("x.json"), Path("y.json")],
        scenario_globs=["a/*.json", "b/*.json"],
        scenario_text=None,
        test_filters=["stamp-*"],
        tag_filters=["stamps"],
        isolation="fresh-server",
    )
    assert options.fail_fast is True
    assert options.accept_eula is True
    assert options.work_dir == tmp_path / "work"
    assert options.out_dir == Path("fukurou-out")
    assert options.paper_channel == "stable"


def test_paper_channel_flag_is_parsed_and_validated(monkeypatch, capsys):
    from fukurou.cli import build_parser

    parser = build_parser()
    assert parser.parse_args(["versions", "latest"]).paper_channel == "stable"
    assert parser.parse_args(["versions", "latest", "--paper-channel", "alpha"]).paper_channel == "alpha"
    run_args = ["run", "--minecraft-version", "26.3", "--scenario", "{}", "--paper-channel", "beta"]
    assert parser.parse_args(run_args).paper_channel == "beta"
    # 未知の値は argparse が入力の誤り（exit 2）にする
    with pytest.raises(SystemExit) as exited:
        parser.parse_args(["versions", "latest", "--paper-channel", "rc"])
    assert exited.value.code == 2
    # versions は値をそのまま resolve_versions へ渡す
    received = []
    monkeypatch.setattr(
        "fukurou.versions.resolve_versions", lambda spec, max_versions, channel: received.append(channel) or ["26.3"]
    )
    assert main(["versions", "latest", "--paper-channel", "alpha"]) == 0
    assert received == ["alpha"]
    assert json.loads(capsys.readouterr().out) == ["26.3"]


def test_parse_java_major():
    assert parse_java_major("    java.specification.version = 25\n") == 25
    assert parse_java_major("    java.specification.version = 1.8\n") == 8
    assert parse_java_major("nothing") is None
