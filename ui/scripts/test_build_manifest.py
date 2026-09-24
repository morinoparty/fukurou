"""build_manifest.py のテスト。標準ライブラリだけで動かす。

実行: python3 -m unittest discover -s ui/scripts （または ui/scripts で python3 -m unittest）
"""

import contextlib
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import build_manifest  # noqa: E402

FIXTURES = SCRIPT_DIR.parent / "fixtures" / "artifacts"


class VersionSortKeyTest(unittest.TestCase):
    def test_numeric_part_wise_order(self):
        versions = ["26.1", "1.21.10", "1.21.9", "1.21.11", "1.20", "1.21"]
        ordered = sorted(versions, key=build_manifest.version_sort_key)
        self.assertEqual(ordered, ["1.20", "1.21", "1.21.9", "1.21.10", "1.21.11", "26.1"])

    def test_prerelease_and_unknown(self):
        ordered = sorted([None, "1.21.11", "1.21.11-pre1", "1.21.10"], key=build_manifest.version_sort_key)
        self.assertEqual(ordered, ["1.21.10", "1.21.11-pre1", "1.21.11", None])


class SafePathTest(unittest.TestCase):
    def test_paths(self):
        self.assertTrue(build_manifest.is_safe_relative_path("screenshots/Alice/a.png"))
        for bad in ["/etc/passwd", "../x.png", "screenshots/../../x", "a\\b", "", None, 3]:
            self.assertFalse(build_manifest.is_safe_relative_path(bad), bad)


class BuildSiteTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        # ui/dist はビューア側の成果物なので、テストでは仮のビューアを使う
        self.viewer = self.tmp / "viewer"
        (self.viewer / "assets").mkdir(parents=True)
        (self.viewer / "index.html").write_text("<!doctype html>")
        (self.viewer / "assets" / "app.js").write_text("//")
        self.artifacts = self.tmp / "artifacts"
        shutil.copytree(FIXTURES, self.artifacts)
        self.out = self.tmp / "site"

    def build(self, *extra, env=None):
        argv = [
            "--artifacts-dir", str(self.artifacts),
            "--out", str(self.out),
            "--viewer-dir", str(self.viewer),
            "--title", "Test",
            *extra,
        ]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(build_manifest.main(argv, env=env or {}), 0)
        return json.loads((self.out / "manifest.json").read_text())

    def write_result(self, artifact, result):
        directory = self.artifacts / artifact
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "result.json").write_text(json.dumps(result))

    def test_fixtures_manifest(self):
        manifest = self.build()
        self.assertEqual([r["id"] for r in manifest["runs"]], ["paper-1.21.9", "paper-1.21.10", "paper-1.21.11"])
        self.assertEqual([r["status"] for r in manifest["runs"]], ["error", "passed", "failed"])
        self.assertEqual(manifest["summary"], {"total": 3, "passed": 1, "failed": 1, "error": 1})
        self.assertEqual(manifest["players"], ["Alice", "Bob"])
        # failure スクリーンショットは shots に含めない
        self.assertEqual(manifest["shots"], ["stamp-thinking-face", "inventory"])
        self.assertIsNone(manifest["runs"][0]["result"])
        self.assertEqual(manifest["warnings"], ["fukurou-paper-1.21.9: result.json missing (job cancelled?)"])
        self.assertIsNone(manifest["ci"])
        self.assertRegex(manifest["generator"]["version"], r"^\d+\.\d+\.\d+")
        self.assertNotEqual(manifest["generator"]["version"], build_manifest.FALLBACK_VERSION)
        self.assertEqual(manifest["runs"][1]["base"], "runs/paper-1.21.10/")
        self.assertTrue((self.out / "runs/paper-1.21.10/screenshots/Bob/inventory.png").is_file())
        self.assertTrue((self.out / "runs/paper-1.21.11/result.json").is_file())
        self.assertTrue((self.out / "runs/paper-1.21.10/logs/server.log").is_file())
        self.assertTrue((self.out / "index.html").is_file())
        self.assertTrue((self.out / "assets/app.js").is_file())
        js = (self.out / "manifest.js").read_text()
        self.assertTrue(js.startswith("window.__FUKUROU_MANIFEST__ = {"))
        self.assertEqual(json.loads(js.split(" = ", 1)[1].rstrip().rstrip(";"))["summary"], manifest["summary"])

    def test_no_include_logs_and_exclude(self):
        (self.artifacts / "fukurou-site").mkdir()
        manifest = self.build("--no-include-logs", "--exclude", "fukurou-site")
        self.assertNotIn("fukurou-site", [r["artifact"] for r in manifest["runs"]])
        self.assertFalse((self.out / "runs/paper-1.21.10/logs").exists())
        self.assertTrue((self.out / "runs/paper-1.21.10/screenshots/Alice/stamp-thinking-face.png").is_file())

    def test_invalid_json_and_missing_id(self):
        (self.artifacts / "fukurou-paper-26.1").mkdir()
        (self.artifacts / "fukurou-paper-26.1" / "result.json").write_text("{not json")
        result = json.loads((FIXTURES / "fukurou-paper-1.21.10/result.json").read_text())
        del result["id"]
        result["minecraft"]["version"] = "1.21.8"
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        ids = [r["id"] for r in manifest["runs"]]
        # id が無ければ artifact 名の末尾（paper-<version>）、壊れた result は error で、どちらもバージョン順に並ぶ
        self.assertEqual(ids[0], "paper-1.21.8")
        self.assertEqual(ids[-1], "paper-26.1")
        self.assertEqual(manifest["runs"][-1]["status"], "error")
        self.assertTrue(any("not valid JSON" in w for w in manifest["warnings"]))

    def test_single_artifact_flat_layout(self):
        # download-artifact は一致が1件だと <artifact 名>/ を作らずに直下へ展開する
        flat = self.tmp / "flat"
        shutil.copytree(FIXTURES / "fukurou-paper-1.21.10", flat)
        self.artifacts = flat
        manifest = self.build()
        self.assertEqual(manifest["summary"], {"total": 1, "passed": 1, "failed": 0, "error": 0})
        run = manifest["runs"][0]
        self.assertEqual((run["id"], run["artifact"]), ("paper-1.21.10", "fukurou-paper-1.21.10"))
        self.assertIsNotNone(run["result"])
        self.assertEqual(manifest["warnings"], [])
        self.assertTrue((self.out / "runs/paper-1.21.10/screenshots/Bob/inventory.png").is_file())

    def test_single_artifact_without_result(self):
        flat = self.tmp / "flat"
        shutil.copytree(FIXTURES / "fukurou-paper-1.21.9", flat)
        self.artifacts = flat
        manifest = self.build()
        self.assertEqual(manifest["summary"]["total"], 1)
        self.assertEqual(manifest["runs"][0]["status"], "error")

    def test_wrong_field_types_do_not_crash(self):
        result = json.loads((FIXTURES / "fukurou-paper-1.21.10/result.json").read_text())
        result["id"] = "paper-1.21.8"
        result["minecraft"]["version"] = "1.21.8"
        result["screenshots"] = 5
        result["steps"] = "oops"
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        run = manifest["runs"][0]
        self.assertEqual((run["result"]["screenshots"], run["result"]["steps"]), ([], []))
        self.assertEqual(len([w for w in manifest["warnings"] if "is not a list" in w]), 2)

    def test_path_traversal_refused(self):
        result = json.loads((FIXTURES / "fukurou-paper-1.21.10/result.json").read_text())
        result["id"] = "paper-1.21.8"
        result["minecraft"]["version"] = "1.21.8"
        result["screenshots"][0]["path"] = "../../secret.png"
        result["steps"][2]["screenshot"] = "/etc/passwd"
        result["logs"].append({"kind": "server", "path": "logs/../../x.log"})
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        run = manifest["runs"][0]
        # 不正なパスは消して警告するだけで、status は result.status と揃えたままにする
        self.assertEqual(run["status"], "passed")
        self.assertEqual(run["result"]["status"], "passed")
        embedded = json.dumps(run["result"])
        self.assertNotIn("..", embedded)
        self.assertNotIn("/etc/passwd", embedded)
        self.assertEqual(len([w for w in manifest["warnings"] if "unsafe path" in w]), 3)

    def test_unsafe_id_falls_back_to_artifact_name(self):
        result = json.loads((FIXTURES / "fukurou-paper-1.21.10/result.json").read_text())
        result["id"] = "../escape"
        self.write_result("fukurou-paper-1.21.10", result)
        # paper-<version> で終わらない artifact 名は、名前そのものを id にする
        self.write_result("custom-name", result)
        manifest = self.build()
        ids = [r["id"] for r in manifest["runs"]]
        self.assertIn("paper-1.21.10", ids)
        self.assertIn("custom-name", ids)
        self.assertFalse((self.tmp / "escape").exists())

    def test_github_env(self):
        summary_file = self.tmp / "summary.md"
        output_file = self.tmp / "output.txt"
        env = {
            "GITHUB_REPOSITORY": "morinoparty/MineStamp",
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_SHA": "abc",
            "GITHUB_RUN_ID": "123",
            "GITHUB_STEP_SUMMARY": str(summary_file),
            "GITHUB_OUTPUT": str(output_file),
        }
        manifest = self.build(env=env)
        self.assertEqual(manifest["ci"]["runUrl"], "https://github.com/morinoparty/MineStamp/actions/runs/123")
        self.assertEqual(manifest["ci"]["sha"], "abc")
        self.assertIn("| paper-1.21.11 | 1.21.11 | failed |", summary_file.read_text())
        outputs = output_file.read_text()
        self.assertIn("status=failed\n", outputs)
        self.assertIn('summary={"total":3,"passed":1,"failed":1,"error":1}\n', outputs)

    def test_empty_artifacts_and_rebuild(self):
        empty = self.tmp / "empty"
        empty.mkdir()
        self.artifacts = empty
        self.build()
        # 前回のサイトへの再生成は許可し、無関係なディレクトリへの出力は拒否する
        manifest = self.build()
        self.assertEqual(manifest["summary"]["total"], 0)
        self.assertEqual(build_manifest.overall_status(manifest["summary"]), "empty")
        self.out = self.tmp / "viewer"
        with self.assertRaises(SystemExit):
            self.build()

    def test_foreign_manifest_json_is_not_deleted(self):
        # manifest.json はよくある名前なので、それだけで前回のサイトとみなして消してはいけない
        project = self.tmp / "project"
        (project / "src").mkdir(parents=True)
        (project / "manifest.json").write_text('{"name": "my pwa"}')
        (project / "index.html").write_text("<!doctype html>")
        (project / "src" / "app.js").write_text("//")
        self.out = project
        with self.assertRaises(SystemExit):
            self.build()
        self.assertTrue((project / "src" / "app.js").is_file())


if __name__ == "__main__":
    unittest.main()
