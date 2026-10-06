"""scripts/summarize-results.sh（ルートのアクションの outputs とステップの要約）のテスト。

標準ライブラリの unittest だけで書き、bash と jq で実際にスクリプトを動かす。
実行: python3 -m unittest scripts/test_summarize_results.py（リポジトリの直下から）
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "summarize-results.sh"
FIXTURE = Path(__file__).resolve().parent.parent / "ui" / "fixtures" / "artifacts" / "fukurou-paper-1.21.11" / "result.json"


def passed_run(run_id: str, label: str | None = None, tests: int = 2) -> dict:
    """すべて passed の最小限の result.json。"""
    return {
        "schemaVersion": 2,
        "id": run_id,
        "label": label,
        "status": "passed",
        "summary": {"total": tests, "passed": tests, "failed": 0, "error": 0, "skipped": 0},
        "tests": [
            {"id": f"t{i}", "status": "passed", "durationMs": 1200, "failure": None, "reset": None, "skipReason": None}
            for i in range(tests)
        ],
        "failure": None,
    }


@unittest.skipUnless(shutil.which("jq") and shutil.which("bash"), "needs bash and jq")
class SummarizeResultsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.out = self.tmp / "out"
        self.out.mkdir()

    def write_run(self, run_id: str, result) -> Path:
        path = self.out / run_id / "result.json"
        path.parent.mkdir(parents=True)
        path.write_text(result if isinstance(result, str) else json.dumps(result), encoding="utf-8")
        return path

    def summarize(self) -> tuple[dict, str]:
        """スクリプトを動かし、GITHUB_OUTPUT の内容（key=value）とステップの要約を返す。"""
        output = self.tmp / "github_output"
        summary = self.tmp / "step_summary"
        output.write_text("")
        summary.write_text("")
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "OUT_DIR": str(self.out),
            "MINECRAFT_VERSION": "1.21.11",
            "GITHUB_OUTPUT": str(output),
            "GITHUB_STEP_SUMMARY": str(summary),
        }
        subprocess.run(["bash", str(SCRIPT)], env=env, check=True, capture_output=True, text=True)
        lines = output.read_text(encoding="utf-8").splitlines()
        outputs = dict(line.split("=", 1) for line in lines)
        # 1 行 1 値で、同じキーが二度出ない（改行を含む値で出力が壊れていない）
        self.assertEqual(len(outputs), len(lines), lines)
        return outputs, summary.read_text(encoding="utf-8")

    def test_no_result_is_an_error(self):
        outputs, summary = self.summarize()
        self.assertEqual(outputs["result"], "error")
        self.assertEqual(json.loads(outputs["tests-summary"])["total"], 0)
        self.assertEqual(outputs["result-file"], "")
        self.assertIn("No result.json", summary)

    def test_runs_are_aggregated(self):
        self.write_run("paper-1.21.11-a", passed_run("paper-1.21.11-a", "a", tests=2))
        self.write_run("paper-1.21.11-b", passed_run("paper-1.21.11-b", "b", tests=3))
        outputs, summary = self.summarize()
        self.assertEqual(outputs["result"], "passed")
        self.assertEqual(
            json.loads(outputs["tests-summary"]),
            {"total": 5, "passed": 5, "failed": 0, "error": 0, "skipped": 0},
        )
        self.assertEqual(outputs["failed-tests"], "")
        self.assertTrue(outputs["result-file"].endswith("paper-1.21.11-a/result.json"))
        self.assertIn("| a | t0 | passed | 1.2s |  |", summary)

    def test_failed_tests_use_label_or_id(self):
        self.write_run("paper-1.21.11", json.loads(FIXTURE.read_text(encoding="utf-8")))
        outputs, summary = self.summarize()
        self.assertEqual(outputs["result"], "failed")
        self.assertEqual(outputs["failed-tests"], "paper-1.21.11/stamp-sleeping-face,paper-1.21.11/stamp-reload")
        self.assertIn("(fixture)", summary)

    def test_errored_or_broken_run_makes_the_result_error(self):
        self.write_run("ok", passed_run("ok"))
        self.write_run("broken", '{"schemaVersion": 2, "tes')
        self.assertEqual(self.summarize()[0]["result"], "error")

        shutil.rmtree(self.out / "broken")
        errored = passed_run("down", "down")
        errored["status"] = "error"
        errored["failure"] = {"phase": None, "message": "server died"}
        self.write_run("down", errored)
        outputs, summary = self.summarize()
        self.assertEqual(outputs["result"], "error")
        self.assertIn("server died", summary)

    def test_label_with_newline_does_not_break_outputs(self):
        # ラベルの改行で GITHUB_OUTPUT に別のキーを書き込めないこと。空のラベルは id で代える
        run = passed_run("paper-1.21.11-x", "x\ninjected=1|y")
        run["status"] = "failed"
        run["tests"][0]["status"] = "failed"
        run["tests"][0]["failure"] = {"phase": "test", "message": "a|b\nc"}
        self.write_run("x", run)
        empty = passed_run("paper-1.21.11-empty", "")
        empty["status"] = "failed"
        empty["tests"][1]["status"] = "error"
        self.write_run("empty", empty)
        outputs, summary = self.summarize()
        self.assertNotIn("injected", outputs)
        self.assertEqual(outputs["failed-tests"], "paper-1.21.11-empty/t1,x injected=1|y/t0")
        self.assertIn("a\\|b c (test)", summary)


if __name__ == "__main__":
    unittest.main()
