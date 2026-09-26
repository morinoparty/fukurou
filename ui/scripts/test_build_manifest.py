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
        self.assertTrue(build_manifest.is_safe_relative_path("tests/t/screenshots/Alice/a.png"))
        for bad in ["/etc/passwd", "../x.png", "screenshots/../../x", "a\\b", "", None, 3]:
            self.assertFalse(build_manifest.is_safe_relative_path(bad), bad)


class ArtifactNameTest(unittest.TestCase):
    def test_trailing_run_id_and_version(self):
        # <prefix>-<server>-<version>[-<label>]。サーバーの種類は paper 以外も読み、入れ子の子は "/" の後ろを見る
        table = [
            ("fukurou-paper-1.21.9", "paper-1.21.9", "1.21.9"),
            ("fukurou-paper-26.3-stamp-arena", "paper-26.3-stamp-arena", "26.3"),
            ("paper-26.3-a-b", "paper-26.3-a-b", "26.3"),
            ("fukurou-velocity-26.3-proxy", "velocity-26.3-proxy", "26.3"),
            ("fukurou-paper-kotlin-26.3/paper-26.3-smoke-arena", "paper-26.3-smoke-arena", "26.3"),
        ]
        for name, run_id, version in table:
            with self.subTest(name=name):
                self.assertEqual(build_manifest.run_id_for(None, name, set(), []), run_id)
                self.assertEqual(build_manifest.version_from_artifact_name(name), version)


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

    def fixture_result(self, version="1.21.10"):
        return json.loads((FIXTURES / f"fukurou-paper-{version}/result.json").read_text())

    def test_fixtures_manifest(self):
        manifest = self.build()
        self.assertEqual(manifest["schemaVersion"], 2)
        self.assertEqual([r["id"] for r in manifest["runs"]], ["paper-1.21.9", "paper-1.21.10", "paper-1.21.11"])
        self.assertEqual([r["status"] for r in manifest["runs"]], ["error", "passed", "failed"])
        self.assertEqual(manifest["summary"]["runs"], {"total": 3, "passed": 1, "failed": 1, "error": 1})
        # 1.21.10 の 4 件 + 1.21.11 の 4 件。result.json の無い 1.21.9 は数えない
        self.assertEqual(
            manifest["summary"]["tests"], {"total": 8, "passed": 5, "failed": 1, "error": 1, "skipped": 1}
        )
        self.assertEqual(manifest["players"], ["Alice", "Bob"])
        self.assertNotIn("shots", manifest)
        self.assertIsNone(manifest["runs"][0]["result"])
        self.assertEqual(manifest["warnings"], ["fukurou-paper-1.21.9: result.json missing (job cancelled?)"])
        self.assertIsNone(manifest["ci"])
        self.assertRegex(manifest["generator"]["version"], r"^\d+\.\d+\.\d+")
        self.assertNotEqual(manifest["generator"]["version"], build_manifest.FALLBACK_VERSION)
        self.assertEqual(manifest["runs"][1]["base"], "runs/paper-1.21.10/")
        # result.json はそのまま載るので、ビューアは minecraft.channel から alpha / beta のバッジを出せる
        self.assertEqual(manifest["runs"][1]["result"]["minecraft"]["channel"], "STABLE")
        self.assertEqual(manifest["runs"][2]["result"]["minecraft"]["channel"], "ALPHA")
        self.assertTrue((self.out / "runs/paper-1.21.10/tests/stamp-thinking-face/screenshots/Bob/after-stamp.png").is_file())
        self.assertTrue((self.out / "runs/paper-1.21.11/result.json").is_file())
        self.assertTrue((self.out / "runs/paper-1.21.10/logs/sessions/1/server.log").is_file())
        self.assertTrue((self.out / "index.html").is_file())
        self.assertTrue((self.out / "assets/app.js").is_file())
        js = (self.out / "manifest.js").read_text()
        self.assertTrue(js.startswith("window.__FUKUROU_MANIFEST__ = {"))
        self.assertEqual(json.loads(js.split(" = ", 1)[1].rstrip().rstrip(";"))["summary"], manifest["summary"])

    def test_test_matrix(self):
        manifest = self.build()
        tests = {t["id"]: t for t in manifest["tests"]}
        # 最初に現れた run（古いバージョン）の実行順で並び、後の run にだけあるテストは末尾に足す
        self.assertEqual(
            [t["id"] for t in manifest["tests"]],
            ["stamp-thinking-face", "stamp-sleeping-face", "stamp-burst", "stamp-legacy-format", "stamp-reload"],
        )
        self.assertEqual(tests["stamp-thinking-face"]["cells"], {"paper-1.21.10": "passed", "paper-1.21.11": "passed"})
        self.assertEqual(tests["stamp-sleeping-face"]["status"], "failed")
        self.assertEqual(tests["stamp-legacy-format"]["cells"], {"paper-1.21.10": "passed", "paper-1.21.11": "skipped"})
        self.assertEqual(tests["stamp-legacy-format"]["status"], "passed")
        # 1.21.10 で走っていないテストはセルが無い（not run）
        self.assertEqual(tests["stamp-reload"]["cells"], {"paper-1.21.11": "error"})
        # players / shots はテストごとの和集合で、failure は shots に含めない
        self.assertEqual(tests["stamp-sleeping-face"]["players"], ["Alice", "Bob"])
        self.assertEqual(tests["stamp-sleeping-face"]["shots"], ["after-stamp"])
        self.assertEqual(tests["stamp-legacy-format"]["players"], ["Alice"])
        self.assertEqual(tests["stamp-legacy-format"]["shots"], ["legacy-stamp"])
        self.assertEqual(tests["stamp-legacy-format"]["tags"], ["stamps", "legacy"])
        self.assertEqual(tests["stamp-reload"]["shots"], [])

    def test_parallel_and_repeat_fields_pass_through(self):
        # fukurou 2.1 のステップの parallel / repeat / startedAt / finishedAt は手を加えずにビューアへ渡す
        manifest = self.build()
        tests = {t["id"]: t for t in manifest["tests"]}
        self.assertEqual(tests["stamp-burst"]["shots"], ["stamp-1", "stamp-2", "stamp-3", "final"])
        run = next(r for r in manifest["runs"] if r["id"] == "paper-1.21.10")
        burst = next(t for t in run["result"]["tests"] if t["id"] == "stamp-burst")
        bob = burst["steps"][4]
        self.assertEqual(bob["parallel"], {"block": 0, "lane": 1})
        self.assertEqual(bob["repeat"], [{"block": 0, "iteration": 1, "of": 3}])
        self.assertTrue(bob["startedAt"] < bob["finishedAt"])
        self.assertEqual(bob["screenshot"], "tests/stamp-burst/screenshots/Bob/stamp-1.png")
        self.assertTrue((self.out / "runs/paper-1.21.10/tests/stamp-burst/screenshots/Bob/final.png").is_file())

    def test_row_status_priority(self):
        # 同じテストが版によって skipped / passed / error なら、行は error
        result = self.fixture_result()
        result["id"] = "paper-1.21.8"
        result["minecraft"]["version"] = "1.21.8"
        result["tests"][0]["status"] = "bogus"
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        thinking = manifest["tests"][0]
        self.assertEqual(thinking["cells"]["paper-1.21.8"], "error")
        self.assertEqual(thinking["status"], "error")

    def test_run_without_result_lists_its_harness_log(self):
        # result.json の無い 1.21.9 はログの一覧を持たないので、コピーした harness.log を run 自身に載せる
        manifest = self.build()
        runs = {r["id"]: r for r in manifest["runs"]}
        self.assertEqual(runs["paper-1.21.9"]["logs"], [{"kind": "harness", "path": "logs/harness.log", "player": None}])
        self.assertTrue((self.out / "runs/paper-1.21.9/logs/harness.log").is_file())
        # 読める result のある run には付けない（result.logs / sessions[].logs を使う）
        self.assertNotIn("logs", runs["paper-1.21.10"])
        # ログをコピーしなければリンク先が無いので付けない
        manifest = self.build("--no-include-logs")
        self.assertNotIn("logs", manifest["runs"][0])

    def test_unsupported_schema_version(self):
        # v1 の result.json は埋め込むが、格子には載せず run を error に数える
        v1 = {"schemaVersion": 1, "id": "paper-1.21.8", "status": "passed", "minecraft": {"version": "1.21.8"},
              "steps": [], "screenshots": [{"path": "screenshots/Alice/a.png"}]}
        self.write_result("fukurou-paper-1.21.8", v1)
        manifest = self.build()
        run = manifest["runs"][0]
        self.assertEqual((run["id"], run["status"]), ("paper-1.21.8", "error"))
        self.assertEqual(run["result"]["schemaVersion"], 1)
        self.assertIn("fukurou-paper-1.21.8: unsupported result schemaVersion 1", manifest["warnings"])
        self.assertTrue(all("paper-1.21.8" not in t["cells"] for t in manifest["tests"]))
        self.assertEqual(manifest["summary"]["runs"]["error"], 2)
        self.assertEqual(manifest["summary"]["tests"]["total"], 8)

    def test_invalid_json_and_missing_id(self):
        (self.artifacts / "fukurou-paper-26.1").mkdir()
        (self.artifacts / "fukurou-paper-26.1" / "result.json").write_text("{not json")
        result = self.fixture_result()
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
        self.assertEqual(manifest["summary"]["runs"], {"total": 1, "passed": 1, "failed": 0, "error": 0})
        self.assertEqual(manifest["summary"]["tests"]["total"], 4)
        run = manifest["runs"][0]
        self.assertEqual((run["id"], run["artifact"]), ("paper-1.21.10", "fukurou-paper-1.21.10"))
        self.assertIsNotNone(run["result"])
        self.assertEqual(manifest["warnings"], [])
        self.assertTrue((self.out / "runs/paper-1.21.10/tests/stamp-legacy-format/screenshots/Alice/legacy-stamp.png").is_file())

    def write_labelled_run(self, directory, version, label):
        # fukurou-kotlin の run ディレクトリ（id は <server>-<version>-<label>、label 付き）を fixture から作る
        shutil.copytree(FIXTURES / f"fukurou-paper-{version}", directory)
        result = json.loads((directory / "result.json").read_text())
        result["id"] = f"paper-{version}-{label}"
        result["label"] = label
        result["fukurou"]["runner"] = "kotlin"
        (directory / "result.json").write_text(json.dumps(result))

    def test_nested_bundles(self):
        # 1 バージョン 1 artifact の中に、サーバーごとの run ディレクトリ（ここでは 1 つ）が入る
        self.artifacts = self.tmp / "nested"
        for version in ("1.21.11", "1.21.10"):
            self.write_labelled_run(self.artifacts / f"fukurou-paper-{version}" / f"paper-{version}-stamp-arena",
                                    version, "stamp-arena")
        output_file = self.tmp / "output.txt"
        manifest = self.build(env={"GITHUB_OUTPUT": str(output_file)})
        runs = manifest["runs"]
        self.assertEqual([r["id"] for r in runs], ["paper-1.21.10-stamp-arena", "paper-1.21.11-stamp-arena"])
        self.assertEqual(runs[0]["artifact"], "fukurou-paper-1.21.10/paper-1.21.10-stamp-arena")
        self.assertEqual([r["label"] for r in runs], ["stamp-arena", "stamp-arena"])
        self.assertEqual(manifest["summary"]["tests"]["total"], 8)
        self.assertEqual(manifest["warnings"], [])
        self.assertTrue(
            (self.out / "runs/paper-1.21.10-stamp-arena/tests/stamp-thinking-face/screenshots/Bob/after-stamp.png").is_file()
        )
        # ラベル付きの run は failed-tests と格子の列見出しを <version>/<label> にする
        self.assertIn(
            "failed-tests=stamp-sleeping-face@1.21.11/stamp-arena,stamp-reload@1.21.11/stamp-arena\n",
            output_file.read_text(),
        )
        self.assertIn("| Test | 1.21.10/stamp-arena | 1.21.11/stamp-arena |", build_manifest.markdown_summary(manifest))

    def test_flattened_nested_bundle(self):
        # 入れ子の束が 1 件だけだと、download-artifact は run ディレクトリを直下に展開する
        self.artifacts = self.tmp / "flattened"
        self.write_labelled_run(self.artifacts / "paper-1.21.10-zeta", "1.21.10", "zeta")
        self.write_labelled_run(self.artifacts / "paper-1.21.10-alpha", "1.21.10", "alpha")
        manifest = self.build()
        # 同じバージョンの run はラベル順に並ぶ
        self.assertEqual(
            [(r["id"], r["artifact"], r["label"]) for r in manifest["runs"]],
            [("paper-1.21.10-alpha", "paper-1.21.10-alpha", "alpha"), ("paper-1.21.10-zeta", "paper-1.21.10-zeta", "zeta")],
        )
        self.assertEqual(manifest["summary"]["runs"]["total"], 2)

    def test_nested_child_without_result(self):
        # 子の 1 つに result.json が無くても run として残し、id とバージョンは子の名前から推測する
        self.artifacts = self.tmp / "nested"
        bundle = self.artifacts / "fukurou-paper-1.21.10"
        self.write_labelled_run(bundle / "paper-1.21.10-stamp-arena", "1.21.10", "stamp-arena")
        (bundle / "paper-1.21.10-broken" / "logs").mkdir(parents=True)
        (bundle / "paper-1.21.10-broken" / "logs" / "harness.log").write_text("boom\n")
        manifest = self.build()
        runs = {r["id"]: r for r in manifest["runs"]}
        self.assertEqual(set(runs), {"paper-1.21.10-stamp-arena", "paper-1.21.10-broken"})
        broken = runs["paper-1.21.10-broken"]
        self.assertEqual((broken["status"], broken["result"], broken["label"]), ("error", None, None))
        self.assertEqual(broken["logs"], [{"kind": "harness", "path": "logs/harness.log", "player": None}])
        self.assertEqual(
            manifest["warnings"], ["fukurou-paper-1.21.10/paper-1.21.10-broken: result.json missing (job cancelled?)"]
        )

    def test_single_artifact_without_result(self):
        # result.json が無くても、直下が契約のディレクトリ（tests/ logs/）だけなら平置きの 1 件とみなす
        flat = self.tmp / "flat"
        shutil.copytree(FIXTURES / "fukurou-paper-1.21.9", flat)
        (flat / "tests" / "stamp-thinking-face").mkdir(parents=True)
        self.artifacts = flat
        manifest = self.build()
        self.assertEqual(manifest["summary"]["runs"]["total"], 1)
        self.assertEqual(manifest["runs"][0]["status"], "error")
        self.assertEqual(manifest["tests"], [])

    def test_wrong_field_types_do_not_crash(self):
        result = self.fixture_result()
        result["id"] = "paper-1.21.8"
        result["minecraft"]["version"] = "1.21.8"
        result["sessions"] = 5
        result["tests"][0]["screenshots"] = 5
        result["tests"][0]["steps"] = "oops"
        result["tests"][0]["logRanges"] = []
        result["tests"].append("not a test")
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        run = manifest["runs"][0]
        first = run["result"]["tests"][0]
        self.assertEqual((run["result"]["sessions"], first["screenshots"], first["steps"]), ([], [], []))
        self.assertIsNone(first["logRanges"])
        self.assertEqual(len(run["result"]["tests"]), 4)
        self.assertEqual(len([w for w in manifest["warnings"] if "is not a list" in w]), 3)
        self.assertTrue(any("not objects" in w for w in manifest["warnings"]))

    def test_unusable_and_duplicate_test_ids(self):
        result = self.fixture_result()
        result["id"] = "paper-1.21.8"
        result["minecraft"]["version"] = "1.21.8"
        result["tests"][1]["id"] = "../escape"
        result["tests"][2]["id"] = "stamp-thinking-face"
        del result["tests"][3]
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        run = manifest["runs"][0]
        self.assertEqual([t["id"] for t in run["result"]["tests"]], ["stamp-thinking-face"])
        self.assertTrue(any("unusable id '../escape'" in w for w in manifest["warnings"]))
        self.assertTrue(any("duplicate test id 'stamp-thinking-face'" in w for w in manifest["warnings"]))
        self.assertTrue(all(".." not in t["id"] for t in manifest["tests"]))

    def test_path_traversal_refused(self):
        result = self.fixture_result()
        result["id"] = "paper-1.21.8"
        result["minecraft"]["version"] = "1.21.8"
        test = result["tests"][0]
        test["screenshots"][0]["path"] = "../../secret.png"
        test["steps"][12]["screenshot"] = "/etc/passwd"
        test["logRanges"]["../outside.log"] = {"from": 1, "to": 2}
        result["sessions"][0]["logs"].append({"kind": "server", "path": "logs/../../x.log", "player": None})
        result["logs"].append({"kind": "harness", "path": "C:\\harness.log", "player": None})
        self.write_result("fukurou-paper-1.21.8", result)
        manifest = self.build()
        run = manifest["runs"][0]
        # 不正なパスは消して警告するだけで、status は result.status と揃えたままにする
        self.assertEqual(run["status"], "passed")
        self.assertEqual(run["result"]["status"], "passed")
        embedded = json.dumps(run["result"])
        self.assertNotIn("..", embedded)
        self.assertNotIn("/etc/passwd", embedded)
        self.assertEqual([log["path"] for log in run["result"]["logs"]], ["logs/harness.log"])
        self.assertEqual(len([w for w in manifest["warnings"] if "unsafe path" in w]), 5)

    def test_missing_screenshot_file_warns(self):
        (self.artifacts / "fukurou-paper-1.21.10/tests/stamp-thinking-face/screenshots/Bob/after-stamp.png").unlink()
        manifest = self.build()
        self.assertIn(
            "fukurou-paper-1.21.10: screenshot file missing: tests/stamp-thinking-face/screenshots/Bob/after-stamp.png",
            manifest["warnings"],
        )

    def test_unsafe_id_falls_back_to_artifact_name(self):
        result = self.fixture_result()
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
        step_summary = summary_file.read_text()
        self.assertIn(
            "| paper-1.21.11 | 1.21.11 | failed | 1/4 | stamp-sleeping-face (fixture), stamp-reload (reset) |",
            step_summary,
        )
        self.assertIn("| Test | 1.21.9 | 1.21.10 | 1.21.11 |", step_summary)
        self.assertIn("| stamp-reload | not run | not run | error |", step_summary)
        outputs = output_file.read_text()
        self.assertIn("status=failed\n", outputs)
        self.assertIn('summary={"total":3,"passed":1,"failed":1,"error":1}\n', outputs)
        self.assertIn('tests-summary={"total":8,"passed":5,"failed":1,"error":1,"skipped":1}\n', outputs)
        self.assertIn("failed-tests=stamp-sleeping-face@1.21.11,stamp-reload@1.21.11\n", outputs)

    def test_run_failure_in_step_summary(self):
        # サーバーが途中で死んだ run は、失敗したテストより run.failure を優先して表に出す
        result = self.fixture_result("1.21.11")
        result["status"] = "error"
        result["failure"] = {"phase": "server", "message": "server died during stamp-reload"}
        self.write_result("fukurou-paper-1.21.11", result)
        manifest = self.build()
        self.assertEqual(manifest["runs"][2]["status"], "error")
        self.assertIn("| error | 1/4 | server: server died during stamp-reload |", build_manifest.markdown_summary(manifest))

    def test_empty_artifacts_and_rebuild(self):
        empty = self.tmp / "empty"
        empty.mkdir()
        self.artifacts = empty
        self.build()
        # 前回のサイトへの再生成は許可し、無関係なディレクトリへの出力は拒否する
        manifest = self.build()
        self.assertEqual(manifest["summary"]["runs"]["total"], 0)
        self.assertEqual(manifest["summary"]["tests"]["total"], 0)
        self.assertEqual(build_manifest.overall_status(manifest["summary"]["runs"]), "empty")
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
