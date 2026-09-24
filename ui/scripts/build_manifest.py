#!/usr/bin/env python3
"""fukurou の artifact 群から、ビューアで閲覧できる静的サイトを組み立てる。

GitHub の標準 ubuntu ランナーで uv なしに動かすため、標準ライブラリだけで書く。
入力は docs/contract.md の「Per-version output directory」、
出力は同じく「Site」の節に従う。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

MANIFEST_SCHEMA_VERSION = 2
# 読み取れる result.json の schemaVersion。それ以外は "unsupported" の run として扱う（v1 の読み取りアダプタは載せない）
RESULT_SCHEMA_VERSION = 2
GENERATOR_NAME = "fukurou-ui"
# pyproject.toml が読めないとき（スクリプトだけ持ち出された場合など）の版
FALLBACK_VERSION = "0.0.0"
# manifest.js で window に載せる変数名。ビューアはこれを最優先で読む
MANIFEST_GLOBAL = "window.__FUKUROU_MANIFEST__"
# 実行結果として受け付けるステータス。それ以外は error として扱う
RUN_STATUSES = ("passed", "failed", "error")
# テストのステータス。それ以外は error として扱う
TEST_STATUSES = ("passed", "failed", "error", "skipped")
# manifest の tests[].status を決める優先順位（「失敗を上に」の並び替え用）。先頭ほど強い
TEST_STATUS_PRIORITY = ("error", "failed", "passed", "skipped")
# shots の和集合から除外するスクリーンショット名（失敗時の自動撮影）
FAILURE_SHOT = "failure"
# run の id はそのままディレクトリ名と URL に使うので、安全な文字だけに限る
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
# テストの id（シナリオファイルの stem）。ビューアのルートと tests/<id>/ のパスになるので契約と同じ規則で検査する
SAFE_TEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
# 契約で artifact のルートに置かれるディレクトリ。1件だけのダウンロードで平置きになったかの判定に使う
CONTRACT_DIRS = ("tests", "logs", "crash-reports")
# ランナーが必ず書く、run 全体のログ（artifact のルートからの相対パス）
HARNESS_LOG = "logs/harness.log"
# result の中でリストであるべきフィールド。型が違うと集計で落ちるので空リストに置き換える
LIST_FIELDS = ("plugins", "players", "sessions", "tests", "logs")
# tests[] の各要素の中でリストであるべきフィールド
TEST_LIST_FIELDS = ("tags", "players", "steps", "screenshots")
# sessions[] の各要素の中でリストであるべきフィールド
SESSION_LIST_FIELDS = ("players", "tests", "logs")
# artifact 名の末尾からバージョンを拾う（例: fukurou-paper-1.21.9 → 1.21.9）
TRAILING_VERSION = re.compile(r"(\d+(?:\.\d+)+(?:-[A-Za-z0-9.]+)?)$")
# artifact 名（<prefix>-<server>-<version>）の末尾から result.id と同じ形の id を拾う（例: fukurou-paper-1.21.9 → paper-1.21.9）
TRAILING_RUN_ID = re.compile(r"(?:^|-)(paper-\d+(?:\.\d+)+(?:-[A-Za-z0-9.]+)?)$")

SCRIPT_DIR = Path(__file__).resolve().parent


def generator_version() -> str:
    """リポジトリの pyproject.toml から fukurou の版を読む。ランナーと ui は同じタグで出すため。"""
    pyproject = SCRIPT_DIR.parent.parent / "pyproject.toml"
    try:
        text = pyproject.read_text(encoding="utf-8")
    except OSError:
        # 読めなくてもサイト生成自体は止めない
        return FALLBACK_VERSION
    # tomllib は 3.11 以降にしか無いので、ubuntu-22.04 の 3.10 でも読めるよう [project] の version 行を直接探す
    project = re.search(r"^\[project\]\s*$(.*?)(?=^\[|\Z)", text, re.M | re.S)
    version = re.search(r'^version\s*=\s*"([^"]+)"', project.group(1), re.M) if project else None
    return version.group(1) if version else FALLBACK_VERSION


def utc_now() -> str:
    """契約の他の時刻と同じく、秒精度の UTC を Z 付きで返す。"""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def version_sort_key(version: str | None) -> tuple:
    """Minecraft のバージョン文字列を、数値として部分ごとに比較できるキーへ変換する。

    1.21.10 > 1.21.9、26.1 > 1.21.11 になる。"-pre1" などの接尾辞付きは同じ数値の正式版より前に並べ、
    読めないバージョンは最後に回す。
    """
    if not version:
        return (1, (), 0, "")
    numeric, _, suffix = version.partition("-")
    parts: list[int] = []
    for part in numeric.split("."):
        # 数字で始まらない部分は 0 とみなし、例外で落とさない
        digits = re.match(r"\d+", part)
        parts.append(int(digits.group()) if digits else 0)
    # 正式版 (接尾辞なし) を 1、プレリリースを 0 にして、同じ数値なら正式版を後ろにする
    return (0, tuple(parts), 0 if suffix else 1, suffix)


def is_safe_relative_path(path: Any) -> bool:
    """result.json 内のパスが artifact ルート配下を指す相対パスかどうか。"""
    if not isinstance(path, str) or not path or "\\" in path:
        return False
    pure = PurePosixPath(path)
    # 絶対パスと ".." を含むパスはサイトの外を指しうるので拒否する
    return not pure.is_absolute() and ".." not in pure.parts


def version_from_artifact_name(name: str) -> str | None:
    """result.json が無いときに、並び替え用のバージョンを artifact 名から推測する。"""
    match = TRAILING_VERSION.search(name)
    return match.group(1) if match else None


def _normalize_lists(container: dict, keys: tuple[str, ...], where: str, artifact: str, warnings: list[str]) -> None:
    """container[key] がリストでなければ警告して空リストに置き換える。"""
    for key in keys:
        if key in container and not isinstance(container[key], list):
            warnings.append(f"{artifact}: result.json field {where}{key!r} is not a list; ignored")
            container[key] = []


def normalize_list_fields(result: dict, artifact: str, warnings: list[str]) -> None:
    """リストであるべきフィールドの型が違えば空リストに置き換える。

    将来の schemaVersion などで型が変わっても、集計やビューアが例外で止まらないようにする。
    tests[] / sessions[] の要素がオブジェクトでなければ、その要素ごと捨てる。
    """
    _normalize_lists(result, LIST_FIELDS, "", artifact, warnings)
    for key, nested in (("tests", TEST_LIST_FIELDS), ("sessions", SESSION_LIST_FIELDS)):
        entries = result.get(key)
        if not isinstance(entries, list):
            continue
        kept = [entry for entry in entries if isinstance(entry, dict)]
        if len(kept) != len(entries):
            warnings.append(f"{artifact}: result.json field {key!r} has entries that are not objects; ignored")
        for entry in kept:
            _normalize_lists(entry, nested, f"{key}[].", artifact, warnings)
        result[key] = kept
    for test in result.get("tests") or []:
        # logRanges はオブジェクト（パス → 行の範囲）か null
        if test.get("logRanges") is not None and not isinstance(test["logRanges"], dict):
            warnings.append(f"{artifact}: result.json field 'tests[].logRanges' is not an object; ignored")
            test["logRanges"] = None


def normalize_tests(result: dict, artifact: str, warnings: list[str]) -> None:
    """ビューアのルートに使えない id と、run 内で重複した id のテストを捨てる。

    テストの id は URL（#/tests/<id>）と tests/<id>/ のパスになるので、不正な値をそのまま載せない。
    """
    seen: set[str] = set()
    kept = []
    for test in result.get("tests") or []:
        test_id = test.get("id")
        if not (isinstance(test_id, str) and SAFE_TEST_ID.match(test_id)):
            warnings.append(f"{artifact}: refused test with unusable id {test_id!r}")
            continue
        if test_id in seen:
            warnings.append(f"{artifact}: duplicate test id {test_id!r}; kept the first")
            continue
        seen.add(test_id)
        kept.append(test)
    if "tests" in result:
        result["tests"] = kept


def sanitize_result_paths(result: dict, artifact: str, warnings: list[str]) -> None:
    """result 内の不正なパスを取り除く。

    ビューアが run の外のファイルを読みに行かないよう、該当エントリは埋め込む前に消す。
    対象は tests[].screenshots, tests[].steps[].screenshot, tests[].logRanges のキー,
    sessions[].logs とルートの logs。
    run の status は変えない（変えるとビューアの result.status 表示と summary が食い違うため）。
    警告で知らせるだけにする。
    """

    def reject(path: Any) -> None:
        warnings.append(f"{artifact}: refused unsafe path in result.json: {path!r}")

    def keep_safe_entries(container: dict, key: str) -> None:
        entries = container.get(key)
        if not isinstance(entries, list):
            return
        kept = []
        for entry in entries:
            path = entry.get("path") if isinstance(entry, dict) else None
            if is_safe_relative_path(path):
                kept.append(entry)
            else:
                reject(path)
        container[key] = kept

    keep_safe_entries(result, "logs")
    for session in result.get("sessions") or []:
        if isinstance(session, dict):
            keep_safe_entries(session, "logs")

    for test in result.get("tests") or []:
        if not isinstance(test, dict):
            continue
        keep_safe_entries(test, "screenshots")
        for step in test.get("steps") or []:
            if isinstance(step, dict) and step.get("screenshot") is not None:
                if not is_safe_relative_path(step["screenshot"]):
                    reject(step["screenshot"])
                    step["screenshot"] = None
        log_ranges = test.get("logRanges")
        if isinstance(log_ranges, dict):
            for path in [p for p in log_ranges if not is_safe_relative_path(p)]:
                reject(path)
                del log_ranges[path]


def load_result(artifact_dir: Path, name: str, warnings: list[str]) -> dict | None:
    """artifact の result.json を読む。無い・壊れている場合は警告を残して None を返す。"""
    path = artifact_dir / "result.json"
    if not path.is_file():
        warnings.append(f"{name}: result.json missing (job cancelled?)")
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as e:
        warnings.append(f"{name}: result.json is not valid JSON ({e})")
        return None
    if not isinstance(data, dict):
        warnings.append(f"{name}: result.json is not a JSON object")
        return None
    return data


def is_supported(result: dict | None) -> bool:
    """このサイト生成が読める result.json かどうか。"""
    return isinstance(result, dict) and result.get("schemaVersion") == RESULT_SCHEMA_VERSION


def run_id_for(result: dict | None, artifact: str, used: set[str], warnings: list[str]) -> str:
    """run の id を決める。result.id が使えなければ artifact 名にし、重複は連番で避ける。"""
    candidate = result.get("id") if result else None
    if not (isinstance(candidate, str) and SAFE_ID.match(candidate) and candidate not in (".", "..")):
        if candidate is not None:
            warnings.append(f"{artifact}: result id {candidate!r} is not usable; using the artifact name")
        # result.json のある run と同じ形の id にそろえ、取れなければ artifact 名を使える文字に置き換えて使う
        match = TRAILING_RUN_ID.search(artifact)
        candidate = match.group(1) if match else re.sub(r"[^A-Za-z0-9._-]", "_", artifact).strip(".") or "run"
    unique = candidate
    counter = 2
    while unique in used:
        unique = f"{candidate}-{counter}"
        counter += 1
    if unique != candidate:
        warnings.append(f"{artifact}: duplicate run id {candidate!r}; using {unique!r}")
    used.add(unique)
    return unique


def copy_tree(source: Path, destination: Path) -> None:
    """ディレクトリをコピーする。シンボリックリンクは artifact の外を指しうるので辿らずに捨てる。"""
    if not source.is_dir() or source.is_symlink():
        return

    def ignore_symlinks(directory: str, names: list[str]) -> list[str]:
        return [n for n in names if os.path.islink(os.path.join(directory, n))]

    shutil.copytree(source, destination, ignore=ignore_symlinks, dirs_exist_ok=True)


def copy_artifact_files(artifact_dir: Path, run_dir: Path, include_logs: bool) -> None:
    """テストごとのスクリーンショット（とログ類）を runs/<id>/ へコピーする。jar やワールドは契約上そもそも含まれない。"""
    run_dir.mkdir(parents=True, exist_ok=True)
    copy_tree(artifact_dir / "tests", run_dir / "tests")
    if include_logs:
        copy_tree(artifact_dir / "logs", run_dir / "logs")
        copy_tree(artifact_dir / "crash-reports", run_dir / "crash-reports")


def warn_missing_screenshots(result: dict, run_dir: Path, artifact: str, warnings: list[str]) -> None:
    """result に載っているのにファイルが無いスクリーンショットを警告する（表示が壊れる原因になるため）。"""
    for test in result.get("tests") or []:
        for shot in test.get("screenshots") or []:
            if isinstance(shot, dict) and not (run_dir / shot["path"]).is_file():
                warnings.append(f"{artifact}: screenshot file missing: {shot['path']}")


def fallback_logs(run_dir: Path) -> list[dict]:
    """読める result が無い run のために、サイトへコピーできた harness.log を LogInfo の形で返す。

    result が無いとログの一覧（result.logs / sessions[].logs）も無い。ビューアはこの一覧から生ファイルへリンクする。
    harness.log はセットアップの最初から書かれるので、失敗の理由が残っている唯一のファイルであることが多い。
    """
    if (run_dir / HARNESS_LOG).is_file():
        return [{"kind": "harness", "path": HARNESS_LOG, "player": None}]
    return []


def build_run(
    artifact_dir: Path,
    artifact: str,
    out: Path,
    include_logs: bool,
    used_ids: set[str],
    warnings: list[str],
) -> dict:
    """artifact 1つ分を runs/<id>/ に展開し、manifest の run エントリを返す。"""
    result = load_result(artifact_dir, artifact, warnings)
    run_id = run_id_for(result, artifact, used_ids, warnings)
    run_dir = out / "runs" / run_id
    copy_artifact_files(artifact_dir, run_dir, include_logs)

    status = "error"
    if result is not None and not is_supported(result):
        # ビューアは未対応の版を "unsupported" として表示するので、埋め込みは続ける。
        # 形が分からないので集計（tests の格子）には入れず、run は error に数える
        warnings.append(f"{artifact}: unsupported result schemaVersion {result.get('schemaVersion')!r}")
    elif result is not None:
        status = result.get("status") if result.get("status") in RUN_STATUSES else "error"
        normalize_list_fields(result, artifact, warnings)
        normalize_tests(result, artifact, warnings)
        sanitize_result_paths(result, artifact, warnings)
        warn_missing_screenshots(result, run_dir, artifact, warnings)
    if result is not None:
        # 取り除いたパスを反映した result をサイト側にも置く
        (run_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    run = {
        "id": run_id,
        "artifact": artifact,
        "base": f"runs/{run_id}/",
        "status": status,
        "result": result,
    }
    # 読める result が無い run（result.json が無い・未対応の版）では、コピーしたログを run 自身に載せる
    if not is_supported(result):
        logs = fallback_logs(run_dir)
        if logs:
            run["logs"] = logs
    return run


def build_run_safely(
    artifact_dir: Path,
    artifact: str,
    out: Path,
    include_logs: bool,
    used_ids: set[str],
    warnings: list[str],
) -> dict:
    """build_run の安全網。想定外の result で例外が出ても、その run だけ error にしてサイト生成は続ける。"""
    try:
        return build_run(artifact_dir, artifact, out, include_logs, used_ids, warnings)
    except Exception as e:  # noqa: BLE001 - 1件の壊れた artifact で全 run の表示を失わないため
        warnings.append(f"{artifact}: could not process the artifact ({type(e).__name__}: {e})")
        run_id = run_id_for(None, artifact, used_ids, warnings)
        return {"id": run_id, "artifact": artifact, "base": f"runs/{run_id}/", "status": "error", "result": None}


def run_tests(run: dict) -> list[dict]:
    """run の tests[]（読める版の result だけ）を実行順に返す。"""
    result = run["result"]
    if not is_supported(result):
        return []
    tests = list(result.get("tests") or [])

    def order_key(item: tuple[int, dict]) -> tuple:
        position, test = item
        order = test.get("order")
        # order が数値でなければ配列の順を使う
        return (order if isinstance(order, int) and not isinstance(order, bool) else position, position)

    return [test for _, test in sorted(enumerate(tests), key=order_key)]


def cell_status(test: dict) -> str:
    """テストのステータス。契約外の値は error に数える。"""
    status = test.get("status")
    return status if status in TEST_STATUSES else "error"


def append_unique(values: list[str], value: Any, exclude: set[str] = frozenset()) -> None:
    """文字列を重複なく末尾に足す。"""
    if isinstance(value, str) and value not in exclude and value not in values:
        values.append(value)


def build_test_matrix(runs: list[dict]) -> list[dict]:
    """テスト × バージョンの格子を作る。

    行は全 run の tests[] の和集合で、runs（古いバージョン順）の中で最初に現れた run の実行順に並べる。
    cells に無い run は「not run」で、error には数えない。
    """
    matrix: dict[str, dict] = {}
    for run in runs:
        for test in run_tests(run):
            entry = matrix.get(test["id"])
            if entry is None:
                name = test.get("name")
                entry = matrix[test["id"]] = {
                    "id": test["id"],
                    "name": name if isinstance(name, str) and name else test["id"],
                    "tags": [],
                    "players": [],
                    "shots": [],
                    "status": "skipped",
                    "cells": {},
                }
            for tag in test.get("tags") or []:
                append_unique(entry["tags"], tag)
            for player in test.get("players") or []:
                append_unique(entry["players"], player.get("name") if isinstance(player, dict) else None)
            for shot in test.get("screenshots") or []:
                append_unique(entry["shots"], shot.get("name") if isinstance(shot, dict) else None, {FAILURE_SHOT})
            entry["cells"][run["id"]] = cell_status(test)
    for entry in matrix.values():
        # error > failed > passed > skipped の順で最も強いものを行のステータスにする
        entry["status"] = min(entry["cells"].values(), key=TEST_STATUS_PRIORITY.index)
    return list(matrix.values())


def summarize_tests(tests: list[dict]) -> dict:
    """テスト × バージョンのセルのステータスごとの件数（not run は数えない）。"""
    summary = {"total": 0, "passed": 0, "failed": 0, "error": 0, "skipped": 0}
    for test in tests:
        for status in test["cells"].values():
            summary["total"] += 1
            summary[status] += 1
    return summary


def failed_tests(manifest: dict) -> list[str]:
    """failed / error のセルを "<test id>@<version>" で返す（テストの順、その中は古いバージョン順）。"""
    versions = {run["id"]: run_version(run) or run["id"] for run in manifest["runs"]}
    return [
        f"{test['id']}@{versions[run_id]}"
        for test in manifest["tests"]
        for run_id in versions
        if test["cells"].get(run_id) in ("failed", "error")
    ]


def is_single_artifact_layout(artifacts_dir: Path) -> bool:
    """artifacts-dir 自体が artifact 1件の中身かどうか。

    download-artifact は一致した artifact が1件だけだと、merge-multiple: false でも
    <artifact 名>/ を作らず path 直下に展開する。そのときは result.json や screenshots/ が直下に来る。
    """
    if (artifacts_dir / "result.json").is_file():
        return True
    subdirs = [p.name for p in artifacts_dir.iterdir() if p.is_dir()]
    # result.json が無くても（キャンセル等）、直下が契約のディレクトリだけなら平置きとみなす
    return bool(subdirs) and all(name in CONTRACT_DIRS for name in subdirs)


def single_artifact_name(artifacts_dir: Path) -> str:
    """平置きの artifact の名前を推測する。

    ダウンロード後は本来の名前が分からないので、既定の命名（fukurou-<result.id>）で復元し、
    それもできなければディレクトリ名を使う。
    """
    try:
        data = json.loads((artifacts_dir / "result.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        data = None
    run_id = data.get("id") if isinstance(data, dict) else None
    if isinstance(run_id, str) and SAFE_ID.match(run_id):
        return f"fukurou-{run_id}"
    return artifacts_dir.resolve().name or "fukurou"


def discover_artifacts(artifacts_dir: Path, excluded: set[str], out: Path) -> list[tuple[Path, str]]:
    """run として読む (ディレクトリ, artifact 名) の一覧を返す。"""
    if is_single_artifact_layout(artifacts_dir):
        return [(artifacts_dir, single_artifact_name(artifacts_dir))]
    out_resolved = out.resolve()
    found = []
    for artifact_dir in sorted(p for p in artifacts_dir.iterdir() if p.is_dir() and not p.is_symlink()):
        # サイト自体の artifact や出力先ディレクトリは run として扱わない
        if artifact_dir.name in excluded or artifact_dir.resolve() == out_resolved:
            continue
        found.append((artifact_dir, artifact_dir.name))
    return found


def run_version(run: dict) -> str | None:
    """並び替えに使うバージョン。result が無ければ artifact 名から推測する。"""
    result = run["result"]
    minecraft = result.get("minecraft") if isinstance(result, dict) else None
    if isinstance(minecraft, dict) and isinstance(minecraft.get("version"), str):
        return minecraft["version"]
    return version_from_artifact_name(run["artifact"])


def summarize(runs: list[dict]) -> dict:
    """ステータスごとの件数。"""
    summary = {"total": len(runs), "passed": 0, "failed": 0, "error": 0}
    for run in runs:
        summary[run["status"]] += 1
    return summary


def union_players(runs: list[dict]) -> list[str]:
    """全 run の result.players の名前を、最初に現れた順で重複なく集める。"""
    seen: list[str] = []
    for run in runs:
        if not is_supported(run["result"]):
            continue
        for entry in run["result"].get("players") or []:
            append_unique(seen, entry.get("name") if isinstance(entry, dict) else None)
    return seen


def ci_from_env(env: dict[str, str]) -> dict | None:
    """GitHub Actions の環境変数から ci ブロックを作る。Actions 外なら None。"""
    repository = env.get("GITHUB_REPOSITORY")
    if not repository:
        return None
    server = env.get("GITHUB_SERVER_URL") or "https://github.com"
    run_id = env.get("GITHUB_RUN_ID") or None
    return {
        "repository": repository,
        "sha": env.get("GITHUB_SHA") or None,
        "ref": env.get("GITHUB_REF") or None,
        "runId": run_id,
        "runAttempt": env.get("GITHUB_RUN_ATTEMPT") or None,
        "runUrl": f"{server.rstrip('/')}/{repository}/actions/runs/{run_id}" if run_id else None,
    }


def overall_status(summary: dict) -> str:
    """action の status 出力（summary は run 単位の件数）。run が無ければ empty、1つでも失敗・エラーがあれば failed。"""
    if summary["total"] == 0:
        return "empty"
    return "passed" if summary["passed"] == summary["total"] else "failed"


def failed_test_label(test: dict) -> str:
    """ステップサマリーの Failure 列に出すテストの短い説明（例: stamp-sleeping-face (fixture)）。"""
    failure = test.get("failure")
    phase = failure.get("phase") if isinstance(failure, dict) else None
    return f"{test['id']} ({phase})" if isinstance(phase, str) else test["id"]


def escape_cell(text: str) -> str:
    """Markdown の表が崩れないように改行とパイプを潰す。"""
    return text.replace("|", "\\|").replace("\n", " ")[:200]


def markdown_summary(manifest: dict) -> str:
    """GITHUB_STEP_SUMMARY と標準出力に出す、run 一覧とテスト × バージョンの表。"""
    runs_summary = manifest["summary"]["runs"]
    tests_summary = manifest["summary"]["tests"]
    lines = [
        f"### fukurou: {manifest['title']}",
        "",
        f"{len(manifest['tests'])} tests × {runs_summary['total']} versions: "
        f"{tests_summary['passed']} passed, {tests_summary['failed']} failed, "
        f"{tests_summary['error']} error, {tests_summary['skipped']} skipped",
        "",
        f"{runs_summary['total']} runs: {runs_summary['passed']} passed, "
        f"{runs_summary['failed']} failed, {runs_summary['error']} error",
        "",
        "| Run | Minecraft | Status | Tests passed | Failure |",
        "| --- | --- | --- | --- | --- |",
    ]
    for run in manifest["runs"]:
        tests = run_tests(run)
        passed = sum(1 for test in tests if cell_status(test) == "passed")
        result = run["result"] if is_supported(run["result"]) else {}
        failure = result.get("failure") if isinstance(result.get("failure"), dict) else None
        if run["result"] is not None and not is_supported(run["result"]):
            message = "unsupported result.json"
        elif failure:
            # インフラの失敗（run.failure）があればそれを優先して出す
            message = f"{failure.get('phase')}: {failure.get('message')}"
        else:
            # 無ければ失敗したテストを「id (phase)」で並べる
            message = ", ".join(failed_test_label(test) for test in tests if cell_status(test) in ("failed", "error"))
        lines.append(
            f"| {run['id']} | {run_version(run) or '?'} | {run['status']} | {passed}/{len(tests)} "
            f"| {escape_cell(message)} |"
        )
    if manifest["tests"]:
        # テスト × バージョンの格子。cells に無い run は not run
        lines += [
            "",
            "| Test | " + " | ".join(run_version(run) or run["id"] for run in manifest["runs"]) + " |",
            "| --- |" + " --- |" * len(manifest["runs"]),
        ]
        for test in manifest["tests"]:
            cells = [test["cells"].get(run["id"], "not run") for run in manifest["runs"]]
            lines.append(f"| {escape_cell(test['id'])} | " + " | ".join(cells) + " |")
    if manifest["warnings"]:
        lines += ["", "Warnings:", ""] + [f"- {w}" for w in manifest["warnings"]]
    return "\n".join(lines) + "\n"


def is_previous_site(out: Path) -> bool:
    """out が以前このスクリプトで生成したサイトかどうか。

    manifest.json はよくあるファイル名なので、名前だけでなく generator と index.html まで確かめる。
    """
    try:
        manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return False
    generator = manifest.get("generator") if isinstance(manifest, dict) else None
    return (
        isinstance(generator, dict)
        and generator.get("name") == GENERATOR_NAME
        and (out / "index.html").is_file()
    )


def prepare_out_dir(out: Path) -> None:
    """出力先を用意する。前回生成したサイトなら丸ごと作り直し、無関係なディレクトリは上書きしない。"""
    if out.exists() and any(out.iterdir()):
        if not is_previous_site(out):
            raise SystemExit(f"error: --out {out} exists and is not an empty directory or a previous fukurou site")
        # 古いハッシュ付き assets が immutable で再アップロードされないよう、中身をすべて消す
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)


def write_manifest(out: Path, manifest: dict) -> None:
    """manifest.json と、file:// でも読める classic script 版の manifest.js を書く。"""
    text = json.dumps(manifest, ensure_ascii=False, indent=2)
    (out / "manifest.json").write_text(text + "\n", encoding="utf-8")
    # "</" を逃がして、万一インライン展開されても script タグが閉じないようにする
    compact = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    (out / "manifest.js").write_text(f"{MANIFEST_GLOBAL} = {compact};\n", encoding="utf-8")


def write_github_files(manifest: dict, env: dict[str, str]) -> None:
    """Actions 上なら、ステップサマリーと action の出力（status / summary / tests-summary / failed-tests）を書く。"""
    step_summary = env.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as f:
            f.write(markdown_summary(manifest))
    github_output = env.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"status={overall_status(manifest['summary']['runs'])}\n")
            f.write(f"summary={json.dumps(manifest['summary']['runs'], separators=(',', ':'))}\n")
            f.write(f"tests-summary={json.dumps(manifest['summary']['tests'], separators=(',', ':'))}\n")
            # テスト id は SAFE_TEST_ID、バージョンは result の値なので、改行を潰して出力の行を壊さない
            failed = ",".join(failed_tests(manifest)).replace("\n", " ").replace("\r", " ")
            f.write(f"failed-tests={failed}\n")


def build_site(args: argparse.Namespace, env: dict[str, str]) -> dict:
    """サイト全体を組み立てて manifest を返す。"""
    artifacts_dir = Path(args.artifacts_dir)
    viewer_dir = Path(args.viewer_dir)
    out = Path(args.out)
    if not artifacts_dir.is_dir():
        raise SystemExit(f"error: --artifacts-dir {artifacts_dir} is not a directory")
    if not (viewer_dir / "index.html").is_file():
        raise SystemExit(f"error: viewer not found: {viewer_dir}/index.html (pass --viewer-dir)")

    prepare_out_dir(out)
    # ビューアを先にコピーし、manifest が dist の同名ファイルで上書きされないようにする
    shutil.copytree(viewer_dir, out, dirs_exist_ok=True)

    warnings: list[str] = []
    used_ids: set[str] = set()
    runs = [
        build_run_safely(artifact_dir, artifact, out, args.include_logs, used_ids, warnings)
        for artifact_dir, artifact in discover_artifacts(artifacts_dir, set(args.exclude), out)
    ]
    if not runs:
        warnings.append(f"no artifacts found in {artifacts_dir}")

    # 古いバージョンから順に並べる。同じバージョンは artifact 名で安定させる
    runs.sort(key=lambda run: (version_sort_key(run_version(run)), run["artifact"]))
    tests = build_test_matrix(runs)
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "generator": {"name": GENERATOR_NAME, "version": generator_version()},
        "generatedAt": utc_now(),
        "title": args.title,
        "ci": ci_from_env(env),
        "summary": {"runs": summarize(runs), "tests": summarize_tests(tests)},
        "players": union_players(runs),
        "tests": tests,
        "runs": runs,
        "warnings": warnings,
    }
    write_manifest(out, manifest)
    return manifest


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the fukurou viewer site from fukurou run artifacts.")
    parser.add_argument(
        "--artifacts-dir",
        required=True,
        help="Directory with one subdirectory per artifact, or the contents of a single artifact.",
    )
    parser.add_argument("--out", required=True, help="Output directory for the site.")
    parser.add_argument("--title", default="fukurou", help="Title shown in the viewer.")
    parser.add_argument(
        "--viewer-dir",
        default=str(SCRIPT_DIR.parent / "dist"),
        help="Prebuilt viewer to copy into the site (default: ui/dist).",
    )
    parser.add_argument(
        "--include-logs",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="Copy logs/ and crash-reports/ into the site (default: yes).",
    )
    parser.add_argument(
        "--exclude",
        action="append",
        default=[],
        metavar="NAME",
        help="Artifact directory name to skip (repeatable), e.g. the site artifact.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None, env: dict[str, str] | None = None) -> int:
    """エントリポイント。run が失敗していてもサイト生成に成功すれば 0 を返す。"""
    args = parse_args(argv)
    env = dict(os.environ) if env is None else env
    manifest = build_site(args, env)
    for warning in manifest["warnings"]:
        print(f"warning: {warning}", file=sys.stderr)
    print(markdown_summary(manifest), end="")
    print(f"Site written to {args.out}")
    write_github_files(manifest, env)
    return 0


if __name__ == "__main__":
    sys.exit(main())
