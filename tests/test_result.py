"""result.json の契約モデルと、実行中に結果を組み立てる ResultRecorder のテスト。"""

import json
from pathlib import Path
import re

from fukurou.result.model import ResultV1, ScreenshotInfo
from fukurou.result.recorder import ResultRecorder
from fukurou.scenario import parse_scenario

CONTRACT = Path(__file__).resolve().parents[1] / "docs" / "contract.md"


def contract_example() -> dict:
    """docs/contract.md の result.json の例を取り出す。"""
    text = CONTRACT.read_text(encoding="utf-8")
    section = text.split("## 2. `result.json`", 1)[1]
    return json.loads(re.search(r"```json\n(.*?)```", section, re.DOTALL).group(1))


def test_contract_example_round_trips():
    example = contract_example()
    result = ResultV1.model_validate(example)
    dumped = json.loads(result.model_dump_json(by_alias=True))
    assert ResultV1.model_validate(dumped) == result
    # 書き出しは camelCase のキーを使う
    assert {"schemaVersion", "startedAt", "durationMs"} <= dumped.keys()
    assert dumped["plugins"][0]["classFileMajor"] == 69
    assert dumped["steps"][1]["durationMs"] == 800


def test_recorder_marks_unrun_steps_as_skipped_and_scenario_failures_as_failed(tmp_path):
    scenario = parse_scenario(
        {
            "players": [{"name": "Alice", "op": True}],
            "steps": [
                {"on": "server", "action": "command", "command": "time set noon"},
                {"on": "Alice", "action": "screenshot", "name": "shot"},
                {"action": "wait", "seconds": 1.5},
            ],
        }
    )
    recorder = ResultRecorder("1.21.11", env={})
    recorder.set_players(scenario.players)
    recorder.set_steps(scenario.steps)
    recorder.mark_joined("Alice")
    recorder.step_passed(0, 12)
    recorder.add_screenshot(ScreenshotInfo(player="Alice", name="failure", path="screenshots/Alice/failure.png", width=1, height=1, stepIndex=1))
    recorder.step_failed(1, 800, "no new screenshot")

    result = recorder.write(tmp_path / "result.json")
    assert json.loads((tmp_path / "result.json").read_text())["status"] == "failed"
    assert not (tmp_path / "result.json.tmp").exists()
    assert result.id == "paper-1.21.11"
    assert [step.status for step in result.steps] == ["passed", "failed", "skipped"]
    assert [step.label for step in result.steps] == ["time set noon", "shot", "1.5s"]
    assert [step.on for step in result.steps] == ["server", "Alice", None]
    assert (result.failure.phase, result.failure.step_index) == ("scenario", 1)
    assert result.players[0].joined and result.ci is None
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", result.started_at)


def test_recorder_keeps_the_first_failure_and_reports_errors():
    recorder = ResultRecorder("1.21.11", env={})
    assert recorder.status == "passed"
    recorder.fail("server-start", "server exited")
    recorder.fail("teardown", "could not stop")
    assert recorder.status == "error"
    assert recorder.build().failure.message == "server exited"


def test_an_interrupt_during_the_scenario_is_an_error_not_a_failure():
    recorder = ResultRecorder("1.21.11", env={})
    recorder.fail("scenario", "interrupted")
    assert recorder.status == "error"


def test_ci_info_is_read_from_github_actions_env():
    env = {
        "GITHUB_ACTIONS": "true",
        "GITHUB_REPOSITORY": "morinoparty/MineStamp",
        "GITHUB_SHA": "abc",
        "GITHUB_REF": "refs/pull/1/merge",
        "GITHUB_RUN_ID": "123",
        "GITHUB_RUN_ATTEMPT": "2",
        "GITHUB_SERVER_URL": "https://github.com",
    }
    ci = json.loads(ResultRecorder("1.21.11", env=env).build().model_dump_json(by_alias=True))["ci"]
    assert ci == {
        "repository": "morinoparty/MineStamp",
        "sha": "abc",
        "ref": "refs/pull/1/merge",
        "runId": "123",
        "runAttempt": "2",
        "serverUrl": "https://github.com",
    }
