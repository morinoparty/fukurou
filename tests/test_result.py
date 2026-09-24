"""result.json の契約の例と、実行中に結果を組み立てる RunRecorder / TestRecorder のテスト。"""

import json
from pathlib import Path
import re

import pytest

from fukurou.result.model import LogRange, ParallelInfo, RepeatInfo, ResetInfo, ResultV2, ScreenshotInfo
from fukurou.result.recorder import NOT_RUN, RunRecorder, TestRecorder
from fukurou.scenario import Selection, discover_tests

CONTRACT = Path(__file__).resolve().parents[1] / "docs" / "contract.md"


def contract_example() -> dict:
    """docs/contract.md の result.json の例を取り出す。"""
    text = CONTRACT.read_text(encoding="utf-8")
    section = text.split("## 2. `result.json`", 1)[1]
    return json.loads(re.search(r"```json\n(.*?)```", section, re.DOTALL).group(1))


@pytest.fixture
def specs(tmp_path):
    """arena fixture を使う 2 テスト（Alice と Bob）を持つスイート。"""
    suite = tmp_path / "fukurou.yml"
    suite.write_text(
        "scenarios: [scenarios/*.json]\n"
        "players: [{name: Alice, op: true}, {name: Bob}]\n"
        "fixtures:\n  arena:\n    - {on: server, action: command, command: 'time set noon'}\n"
        "    - {on: Bob, action: press_key, key: F5}\n",
        encoding="utf-8",
    )
    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    (scenarios / "first.json").write_text(
        json.dumps({"use": ["arena"], "players": [{"name": "Alice", "op": True}], "steps": [
            {"on": "Alice", "action": "screenshot", "name": "shot"},
            {"action": "wait", "seconds": 1.5},
        ]}),
        encoding="utf-8",
    )
    (scenarios / "second.json").write_text(json.dumps({"tags": ["x"], "steps": [{"action": "wait", "seconds": 1}]}), encoding="utf-8")
    _, tests = discover_tests(Selection(suite=suite))
    return tests


def test_contract_example_round_trips():
    example = contract_example()
    result = ResultV2.model_validate(example)
    dumped = json.loads(result.model_dump_json(by_alias=True))
    assert ResultV2.model_validate(dumped) == result
    assert {"schemaVersion", "startedAt", "durationMs", "sessions", "tests"} <= dumped.keys()
    assert dumped["tests"][0]["logRanges"]["logs/sessions/0/server.log"] == {"from": 212, "to": 240}


def test_registered_tests_start_as_not_run_and_write_atomically(tmp_path, specs):
    recorder = RunRecorder("1.21.11", env={})
    recorder.register_tests(specs)
    recorder.set_players(["Alice", "Bob"])
    result = recorder.write(tmp_path / "result.json")
    assert json.loads((tmp_path / "result.json").read_text())["status"] == "passed"
    assert not (tmp_path / "result.json.tmp").exists()
    assert result.id == "paper-1.21.11"
    assert [(test.status, test.skip_reason, test.session) for test in result.tests] == [("skipped", NOT_RUN, None)] * 2
    assert result.summary.model_dump() == {"total": 2, "passed": 0, "failed": 0, "error": 0, "skipped": 2}
    assert [player.joined for player in result.players] == [False, False]
    assert result.ci is None
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", result.started_at)


def test_test_recorder_records_phases_failures_and_skipped_steps(specs):
    recorder = TestRecorder(specs[0])
    # 展開済みのステップ: fixture の 2 つ（Bob は居ないので理由付き）→ テストの 2 つ
    assert [(step.phase, step.fixture, step.on, step.label) for step in recorder.steps] == [
        ("fixture", "arena", "server", "time set noon"),
        ("fixture", "arena", "Bob", "F5"),
        ("test", None, "Alice", "shot"),
        ("test", None, None, "1.5s"),
    ]
    recorder.start(session=0)
    recorder.set_reset(ResetInfo(duration_ms=12))
    recorder.step_passed(0, 10)
    recorder.step_skipped(1, "player Bob is not in this test")
    recorder.add_screenshot(ScreenshotInfo(player="Alice", name="failure", path="tests/first/screenshots/Alice/failure.png", width=1, height=1, step_index=2))
    recorder.step_failed(2, 800, "no new screenshot")
    recorder.set_log_ranges({"logs/sessions/0/server.log": LogRange(from_line=3, to=9)})
    recorder.finish()

    result = recorder.build()
    assert result.status == "failed"
    assert [step.status for step in result.steps] == ["passed", "skipped", "failed", "skipped"]
    assert result.steps[1].error == "player Bob is not in this test"
    assert (result.failure.phase, result.failure.step_index, result.session) == ("scenario", 2, 0)
    assert result.log_ranges["logs/sessions/0/server.log"].to == 9
    assert result.duration_ms is not None and result.players[0].op is True


def test_fixture_step_failure_is_failed_and_harness_failures_are_errors(specs):
    fixture = TestRecorder(specs[0])
    fixture.start(0)
    fixture.step_failed(0, 5, "Unknown command")
    fixture.finish()
    assert (fixture.status, fixture.failure.phase) == ("failed", "fixture")

    reset = TestRecorder(specs[0])
    reset.start(0)
    reset.set_reset(ResetInfo(duration_ms=3, error="fill ...: Too many blocks"))
    reset.finish()
    assert (reset.status, reset.failure.phase, reset.build().reset.error) == ("error", "reset", "fill ...: Too many blocks")

    client = TestRecorder(specs[1])
    client.start(0)
    client.step_failed(0, 5, "Alice: client exited with code 1", phase="client")
    assert (client.status, client.failure.phase, client.steps[0].status) == ("error", "client", "failed")

    timeout = TestRecorder(specs[1])
    timeout.start(0)
    timeout.fail("timeout", "too slow")
    timeout.fail("client", "later")
    timeout.finish()
    assert timeout.status == "error" and timeout.failure.message == "too slow"

    passed = TestRecorder(specs[1])
    passed.start(0)
    passed.step_passed(0, 1)
    passed.finish()
    assert passed.status == "passed"


def test_skip_clears_the_partial_run(specs):
    recorder = TestRecorder(specs[0])
    recorder.start(1)
    recorder.step_passed(0, 1)
    recorder.skip("interrupted")
    result = recorder.build()
    assert (result.status, result.skip_reason, result.session, result.reset, result.log_ranges) == ("skipped", "interrupted", None, None, None)
    assert result.steps == [] and result.started_at is None
    # 内部のステップは skipped に戻っている
    assert [step.status for step in recorder.steps] == ["skipped"] * 4


def test_run_status_sessions_and_pending_skips(specs):
    recorder = RunRecorder("1.21.11", env={})
    recorder.register_tests(specs)
    session = recorder.add_session("initial")
    assert (session.index, session.kind) == (0, "initial")
    assert recorder.add_session("fresh-server").index == 1
    recorder.test("first").start(0)
    recorder.test("first").finish()
    assert recorder.status == "passed"
    assert recorder.skip_pending("fail-fast") == ["second"]
    assert recorder.test("second").skip_reason == "fail-fast"

    recorder.finish_session(0, [session.logs[0]] if session.logs else [], failure="server exited")
    recorder.fail("server", "server died during first")
    recorder.fail("teardown", "could not stop")
    result = recorder.build()
    assert result.status == "error" and result.failure.message == "server died during first"
    assert result.sessions[0].failure == "server exited" and result.sessions[0].finished_at is not None
    assert result.summary.model_dump() == {"total": 2, "passed": 1, "failed": 0, "error": 0, "skipped": 1}


def test_ci_info_is_read_from_github_actions_env():
    env = {
        "GITHUB_ACTIONS": "true",
        "GITHUB_REPOSITORY": "example/plugin",
        "GITHUB_SHA": "abc",
        "GITHUB_REF": "refs/pull/1/merge",
        "GITHUB_RUN_ID": "123",
        "GITHUB_RUN_ATTEMPT": "2",
        "GITHUB_SERVER_URL": "https://github.com",
    }
    ci = json.loads(RunRecorder("1.21.11", env=env).build().model_dump_json(by_alias=True))["ci"]
    assert ci == {
        "repository": "example/plugin",
        "sha": "abc",
        "ref": "refs/pull/1/merge",
        "runId": "123",
        "runAttempt": "2",
        "serverUrl": "https://github.com",
    }


def test_steps_carry_their_block_positions_and_timestamps(tmp_path):
    """parallel / repeat の位置は計画から写し、実行したステップだけが startedAt / finishedAt を持つ。"""
    scenarios = tmp_path / "scenarios"
    scenarios.mkdir()
    (scenarios / "blocks.json").write_text(
        json.dumps({"players": [{"name": "Alice"}, {"name": "Bob"}], "steps": [
            {"action": "repeat", "times": 2, "steps": [
                {"on": ["Alice", "Bob"], "action": "screenshot", "name": "shot-${i}"},
            ]},
            {"action": "wait", "seconds": 1},
        ]}),
        encoding="utf-8",
    )
    _, (spec,) = discover_tests(Selection(scenario_files=[scenarios / "blocks.json"]))
    recorder = TestRecorder(spec)
    assert [(s.parallel, s.repeat) for s in recorder.steps] == [
        (ParallelInfo(block=0, lane=0), [RepeatInfo(block=0, iteration=1, of=2)]),
        (ParallelInfo(block=0, lane=1), [RepeatInfo(block=0, iteration=1, of=2)]),
        (ParallelInfo(block=1, lane=0), [RepeatInfo(block=0, iteration=2, of=2)]),
        (ParallelInfo(block=1, lane=1), [RepeatInfo(block=0, iteration=2, of=2)]),
        (None, None),
    ]
    recorder.start(0)
    recorder.step_started(0)
    recorder.step_passed(0, 5)
    recorder.step_started(1)
    recorder.step_failed(1, 7, "boom")
    recorder.finish()
    result = json.loads(recorder.build().model_dump_json(by_alias=True))
    first, second, *rest = result["steps"]
    assert first["parallel"] == {"block": 0, "lane": 0} and first["repeat"] == [{"block": 0, "iteration": 1, "of": 2}]
    # ステップの時刻はミリ秒精度の UTC
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z", first["startedAt"])
    assert first["startedAt"] <= first["finishedAt"] and second["finishedAt"] is not None
    assert all(step["startedAt"] is None and step["finishedAt"] is None for step in rest)
    assert result["failure"]["stepIndex"] == 1
    # skip は実行の痕跡を消すが、ブロック内の位置は残す
    recorder.skip("interrupted")
    assert recorder.steps[0].started_at is None and recorder.steps[0].finished_at is None
    assert recorder.steps[0].parallel == ParallelInfo(block=0, lane=0)


def test_late_updates_after_finish_are_ignored_and_a_failure_is_kept(specs):
    """置き去りにしたレーンが遅れて終わっても、閉じたテストの記録と timeout の判断は変わらない。"""
    recorder = TestRecorder(specs[0])
    recorder.start(0)
    recorder.step_started(0)
    recorder.step_failed(0, None, "the test exceeded its timeout of 1s during step 0", phase="timeout")
    # 遅れて届いた passed はハーネスの判断（failed）を上書きせず、そのステップが遅れて撮った画像も載せない
    recorder.add_screenshot(ScreenshotInfo(player="Alice", name="shot", path="late.png", width=1, height=1, step_index=0))
    recorder.step_passed(0, 2000, screenshot="late.png")
    assert recorder.steps[0].status == "failed" and recorder.steps[0].screenshot is None and recorder.screenshots == []
    recorder.finish()
    recorder.step_passed(1, 1)
    recorder.step_failed(2, 1, "late failure")
    recorder.add_screenshot(ScreenshotInfo(player="Alice", name="late", path="x.png", width=1, height=1))
    assert [step.status for step in recorder.steps] == ["failed", "skipped", "skipped", "skipped"]
    assert recorder.screenshots == [] and recorder.failure.phase == "timeout"
    assert recorder.status == "error"


def test_skip_is_terminal_and_ignores_late_lane_updates(specs):
    """中断で放棄したテストに、置き去りにしたレーンが遅れて記録しても痕跡が残らない。"""
    recorder = TestRecorder(specs[0])
    recorder.start(0)
    recorder.step_started(0)
    recorder.skip("interrupted")
    recorder.add_screenshot(ScreenshotInfo(player="Alice", name="shot", path="late.png", width=1, height=1, step_index=0))
    recorder.step_passed(0, 10, screenshot="late.png")
    recorder.step_failed(1, 10, "late failure")
    result = recorder.build()
    assert (result.status, result.skip_reason, result.failure, result.screenshots, result.steps) == ("skipped", "interrupted", None, [], [])
    assert [step.status for step in recorder.steps] == ["skipped"] * 4


def test_a_passed_step_never_keeps_a_stale_error(specs):
    recorder = TestRecorder(specs[0])
    recorder.start(0)
    # skipped の理由（error）が入ったステップが後で走って通っても、error は残らない
    recorder.step_skipped(0, "player Bob is not in this test")
    recorder.step_passed(0, 10)
    assert recorder.steps[0].status == "passed" and recorder.steps[0].error is None
