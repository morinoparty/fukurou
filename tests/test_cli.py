"""CLI の validate / schema と、ネットワークを使う前に失敗する run の経路のテスト。"""

import json
from pathlib import Path

import pytest

from fukurou.cli import main
from fukurou.java import parse_java_major
from fukurou.schema import SCHEMA_NAMES, schema_json

ROOT = Path(__file__).resolve().parents[1]
SMOKE = ROOT / "examples" / "smoke.json"


def test_validate_accepts_valid_scenarios(capsys):
    assert main(["validate", "--scenario-file", str(SMOKE)]) == 0
    assert "smoke: valid" in capsys.readouterr().out
    inline = "players: [{name: Alice}]\nsteps: [{action: wait, seconds: 1}]"
    assert main(["validate", "--scenario", inline]) == 0


def test_validate_rejects_invalid_scenarios(tmp_path, capsys):
    assert main(["validate", "--scenario", '{"players": [], "steps": []}']) == 2
    assert main(["validate", "--scenario-file", str(tmp_path / "missing.json")]) == 2
    assert "invalid scenario" in capsys.readouterr().err
    with pytest.raises(SystemExit):
        # --scenario と --scenario-file はどちらか一方だけ
        main(["validate", "--scenario", "x", "--scenario-file", str(SMOKE)])


@pytest.mark.parametrize("name", SCHEMA_NAMES)
def test_schema_command_prints_the_committed_schema(name, capsys):
    """schema/*.json が fukurou schema の出力とずれていたら失敗する（更新は fukurou schema で生成し直す）。"""
    assert main(["schema", name]) == 0
    printed = capsys.readouterr().out
    committed = (ROOT / "schema" / f"{name}.v1.json").read_text(encoding="utf-8")
    assert printed == committed == schema_json(name)
    assert json.loads(printed)["$schema"].startswith("https://json-schema.org/")


def run(tmp_path, *extra):
    return main(
        [
            "run",
            "--minecraft-version",
            "1.21.11",
            "--scenario-file",
            str(SMOKE),
            "--plugins",
            "",
            "--work-dir",
            str(tmp_path / "work"),
            "--out-dir",
            str(tmp_path / "out"),
            *extra,
        ]
    )


def read_result(tmp_path) -> dict:
    return json.loads((tmp_path / "out" / "result.json").read_text(encoding="utf-8"))


def test_run_without_eula_writes_an_error_result(tmp_path, capsys):
    assert run(tmp_path) == 2
    result = read_result(tmp_path)
    assert result["status"] == "error"
    assert result["failure"]["phase"] == "setup"
    assert "Minecraft EULA" in result["failure"]["message"]
    assert result["id"] == "paper-1.21.11"
    assert result["logs"] == [{"kind": "harness", "path": "logs/harness.log", "player": None}]
    assert "Minecraft EULA" in (tmp_path / "out" / "logs" / "harness.log").read_text()


def test_run_records_the_scenario_and_skipped_steps_before_network(tmp_path):
    assert run(tmp_path, "--accept-eula", "--java", str(tmp_path / "no-java")) == 2
    result = read_result(tmp_path)
    assert result["failure"]["message"].startswith("Java executable")
    assert result["scenario"]["name"] == "smoke"
    assert result["scenario"]["source"] == f"file:{SMOKE.as_posix()}"
    assert [step["status"] for step in result["steps"]] == ["skipped"] * 4
    assert result["players"] == [{"name": "Alice", "op": True, "joined": False}]


def test_run_with_an_invalid_scenario_exits_2(tmp_path):
    code = main(
        ["run", "--minecraft-version", "1.21.11", "--scenario", "players: []", "--accept-eula",
         "--out-dir", str(tmp_path / "out"), "--work-dir", str(tmp_path / "work")]
    )
    assert code == 2
    result = read_result(tmp_path)
    assert result["scenario"]["source"] == "inline"
    assert result["failure"]["message"].startswith("invalid scenario")


def test_run_accepts_only_a_single_version(tmp_path):
    code = main(
        ["run", "--minecraft-version", "latest", "--scenario-file", str(SMOKE), "--plugins", "", "--accept-eula",
         "--out-dir", str(tmp_path / "out"), "--work-dir", str(tmp_path / "work")]
    )
    assert code == 2
    assert "single Minecraft version" in read_result(tmp_path)["failure"]["message"]


def test_run_refuses_to_clean_directories_it_did_not_create(tmp_path):
    (tmp_path / "out" / "logs").mkdir(parents=True)
    (tmp_path / "out" / "logs" / "mine.log").write_text("keep")
    assert run(tmp_path) == 2
    assert (tmp_path / "out" / "logs" / "mine.log").read_text() == "keep"
    assert "refusing to delete logs" in read_result(tmp_path)["failure"]["message"]


def test_parse_java_major():
    assert parse_java_major("    java.specification.version = 25\n") == 25
    assert parse_java_major("    java.specification.version = 1.8\n") == 8
    assert parse_java_major("nothing") is None
