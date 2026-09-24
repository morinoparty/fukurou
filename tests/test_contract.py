"""result.json schemaVersion 2 の契約モデルと、ui/fixtures の result.json が一致していることのテスト。"""

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from fukurou.result.model import (
    SCHEMA_VERSION,
    LogRange,
    ResultV2,
    RunFailure,
    StepResult,
    TestResult,
    derive_run_status,
    summarize_tests,
)

FIXTURES = Path(__file__).resolve().parent.parent / "ui" / "fixtures" / "artifacts"
FIXTURE_RESULTS = sorted(FIXTURES.glob("*/result.json"))


def _test(status: str, **extra) -> TestResult:
    """最小限のフィールドで TestResult を作る。"""
    return TestResult(id="t", name="t", order=0, source="inline", sha256="0" * 64, status=status, **extra)


def test_log_range_keeps_the_from_key():
    # from は Python の予約語なので、属性名と JSON のキーが食い違わないことを確かめる
    log_range = LogRange.model_validate({"from": 212, "to": 240})
    assert log_range.from_line == 212
    assert json.loads(log_range.model_dump_json()) == {"from": 212, "to": 240}


def test_skipped_test_requires_a_reason():
    with pytest.raises(ValidationError):
        _test("skipped")
    assert _test("skipped", skip_reason="not run").skip_reason == "not run"


def test_fixture_name_only_for_fixture_phase():
    StepResult(index=0, phase="fixture", fixture="arena", action="wait", label="2s", status="passed")
    with pytest.raises(ValidationError):
        StepResult(index=0, phase="fixture", action="wait", label="2s", status="passed")
    with pytest.raises(ValidationError):
        StepResult(index=0, phase="test", fixture="arena", action="wait", label="2s", status="passed")


def test_step_positions_in_blocks_are_optional_camel_case_fields():
    plain = StepResult(index=0, action="wait", label="2s", status="passed")
    assert (plain.parallel, plain.repeat, plain.started_at, plain.finished_at) == (None, None, None, None)
    data = {
        "index": 3, "phase": "test", "on": "Alice", "action": "screenshot", "label": "s1", "status": "passed",
        "parallel": {"block": 0, "lane": 1},
        "repeat": [{"block": 0, "iteration": 1, "of": 2}, {"block": 1, "iteration": 2, "of": 3}],
        "startedAt": "2026-09-24T03:02:10.120Z", "finishedAt": "2026-09-24T03:02:11.900Z",
    }
    step = StepResult.model_validate(data)
    dumped = json.loads(step.model_dump_json())
    assert {key: dumped[key] for key in data} == data
    # 位置情報が無いことは null で表し、空の一覧は使わない
    with pytest.raises(ValidationError):
        StepResult.model_validate(data | {"repeat": []})


def test_run_status_and_summary_are_derived_from_tests():
    tests = [_test("passed"), _test("skipped", skip_reason="fail-fast"), _test("error")]
    assert summarize_tests(tests).model_dump() == {"total": 3, "passed": 1, "failed": 0, "error": 1, "skipped": 1}
    assert derive_run_status(None, tests[:2]) == "passed"
    assert derive_run_status(None, tests) == "failed"
    assert derive_run_status(RunFailure(phase="server", message="server died"), tests[:1]) == "error"


def test_json_schema_uses_camel_case():
    schema = ResultV2.model_json_schema(by_alias=True)
    assert schema["properties"]["schemaVersion"]["const"] == SCHEMA_VERSION == 2
    test_result = schema["$defs"]["TestResult"]["properties"]
    assert {"skipReason", "logRanges", "startedAt", "durationMs"} <= set(test_result)
    assert set(schema["$defs"]["LogRange"]["properties"]) == {"from", "to"}
    # repeat は null か空でない一覧（モデルの規則がスキーマにも表れている）
    repeat = schema["$defs"]["StepResult"]["properties"]["repeat"]["anyOf"]
    assert {"type": "array", "items": {"$ref": "#/$defs/RepeatInfo"}, "minItems": 1} in repeat


def test_fixtures_exist():
    # ビューアとサイト生成のテストが使う v2 の fixture が 2 バージョン分あること
    assert len(FIXTURE_RESULTS) >= 2


@pytest.mark.parametrize("path", FIXTURE_RESULTS, ids=lambda p: p.parent.name)
def test_fixture_results_follow_the_contract(path: Path):
    data = json.loads(path.read_text(encoding="utf-8"))
    result = ResultV2.model_validate(data)
    # 書き出しても同じ JSON に戻る（camelCase のキーと null の扱いが契約どおり）
    assert ResultV2.model_validate_json(result.model_dump_json()) == result
    # summary と status は tests と矛盾しない
    assert result.summary == summarize_tests(result.tests)
    assert result.status == derive_run_status(result.failure, result.tests)

    artifact = path.parent
    sessions = {session.index for session in result.sessions}
    log_paths = {log.path for session in result.sessions for log in session.logs} | {log.path for log in result.logs}
    for log_path in log_paths:
        assert (artifact / log_path).is_file(), log_path
    for test in result.tests:
        assert (test.session is None) == (test.status == "skipped")
        if test.session is not None:
            assert test.session in sessions
        for index, step in enumerate(test.steps):
            assert step.index == index
        for shot in test.screenshots:
            # スクリーンショットは tests/<id>/screenshots/<player>/<name>.png に置く
            assert shot.path == f"tests/{test.id}/screenshots/{shot.player}/{shot.name}.png"
            assert (artifact / shot.path).is_file(), shot.path
        for log_path, log_range in (test.log_ranges or {}).items():
            # logRanges のキーはセッションで回収したログのパスで、行番号はファイルの範囲内
            assert log_path in log_paths
            lines = (artifact / log_path).read_text(encoding="utf-8").splitlines()
            assert 1 <= log_range.from_line <= log_range.to <= len(lines)
