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

MANIFEST_SCHEMA_VERSION = 1
GENERATOR_NAME = "fukurou-ui"
# pyproject.toml が読めないとき（スクリプトだけ持ち出された場合など）の版
FALLBACK_VERSION = "0.0.0"
# manifest.js で window に載せる変数名。ビューアはこれを最優先で読む
MANIFEST_GLOBAL = "window.__FUKUROU_MANIFEST__"
# 実行結果として受け付けるステータス。それ以外は error として扱う
RUN_STATUSES = ("passed", "failed", "error")
# shots の和集合から除外するスクリーンショット名（失敗時の自動撮影）
FAILURE_SHOT = "failure"
# run の id はそのままディレクトリ名と URL に使うので、安全な文字だけに限る
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]+$")
# 契約で artifact のルートに置かれるディレクトリ。1件だけのダウンロードで平置きになったかの判定に使う
CONTRACT_DIRS = ("screenshots", "logs", "crash-reports")
# result の中でリストであるべきフィールド。型が違うと集計で落ちるので空リストに置き換える
LIST_FIELDS = ("players", "steps", "screenshots", "logs")
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


def normalize_list_fields(result: dict, artifact: str, warnings: list[str]) -> None:
    """リストであるべきフィールドの型が違えば空リストに置き換える。

    将来の schemaVersion などで型が変わっても、集計やビューアが例外で止まらないようにする。
    """
    for key in LIST_FIELDS:
        if key in result and not isinstance(result[key], list):
            warnings.append(f"{artifact}: result.json field {key!r} is not a list; ignored")
            result[key] = []


def sanitize_result_paths(result: dict, artifact: str, warnings: list[str]) -> None:
    """result 内の不正なパスを取り除く。

    ビューアが run の外のファイルを読みに行かないよう、該当エントリは埋め込む前に消す。
    run の status は変えない（変えるとビューアの result.status 表示と summary が食い違うため）。
    警告で知らせるだけにする。
    """

    def reject(path: Any) -> None:
        warnings.append(f"{artifact}: refused unsafe path in result.json: {path!r}")

    for key in ("screenshots", "logs"):
        entries = result.get(key)
        if not isinstance(entries, list):
            continue
        kept = []
        for entry in entries:
            path = entry.get("path") if isinstance(entry, dict) else None
            if is_safe_relative_path(path):
                kept.append(entry)
            else:
                reject(path)
        result[key] = kept

    steps = result.get("steps")
    if isinstance(steps, list):
        for step in steps:
            if isinstance(step, dict) and step.get("screenshot") is not None:
                if not is_safe_relative_path(step["screenshot"]):
                    reject(step["screenshot"])
                    step["screenshot"] = None


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
    if data.get("schemaVersion") != 1:
        # ビューアは未対応の版を "unsupported" として表示するので、埋め込みは続ける
        warnings.append(f"{name}: unsupported result schemaVersion {data.get('schemaVersion')!r}")
    return data


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
    """スクリーンショット（とログ類）を runs/<id>/ へコピーする。jar やワールドは契約上そもそも含まれない。"""
    run_dir.mkdir(parents=True, exist_ok=True)
    copy_tree(artifact_dir / "screenshots", run_dir / "screenshots")
    if include_logs:
        copy_tree(artifact_dir / "logs", run_dir / "logs")
        copy_tree(artifact_dir / "crash-reports", run_dir / "crash-reports")


def warn_missing_screenshots(result: dict, run_dir: Path, artifact: str, warnings: list[str]) -> None:
    """result に載っているのにファイルが無いスクリーンショットを警告する（表示が壊れる原因になるため）。"""
    for shot in result.get("screenshots") or []:
        if isinstance(shot, dict) and not (run_dir / shot["path"]).is_file():
            warnings.append(f"{artifact}: screenshot file missing: {shot['path']}")


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
    if result is not None:
        status = result.get("status") if result.get("status") in RUN_STATUSES else "error"
        normalize_list_fields(result, artifact, warnings)
        sanitize_result_paths(result, artifact, warnings)
        warn_missing_screenshots(result, run_dir, artifact, warnings)
        # 取り除いたパスを反映した result をサイト側にも置く
        (run_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    return {
        "id": run_id,
        "artifact": artifact,
        "base": f"runs/{run_id}/",
        "status": status,
        "result": result,
    }


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


def union_in_order(runs: list[dict], key: str, field: str, exclude: set[str] = frozenset()) -> list[str]:
    """全 run の result[key][*][field] を、最初に現れた順で重複なく集める。"""
    seen: list[str] = []
    for run in runs:
        for entry in (run["result"] or {}).get(key) or []:
            value = entry.get(field) if isinstance(entry, dict) else None
            if isinstance(value, str) and value not in exclude and value not in seen:
                seen.append(value)
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
    """action の status 出力。run が無ければ empty、1つでも失敗・エラーがあれば failed。"""
    if summary["total"] == 0:
        return "empty"
    return "passed" if summary["passed"] == summary["total"] else "failed"


def markdown_summary(manifest: dict) -> str:
    """GITHUB_STEP_SUMMARY と標準出力に出す run 一覧の表。"""
    s = manifest["summary"]
    lines = [
        f"### fukurou: {manifest['title']}",
        "",
        f"{s['total']} runs: {s['passed']} passed, {s['failed']} failed, {s['error']} error",
        "",
        "| Run | Minecraft | Status | Steps | Screenshots | Failure |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for run in manifest["runs"]:
        result = run["result"] or {}
        steps = result.get("steps") or []
        passed_steps = sum(1 for step in steps if isinstance(step, dict) and step.get("status") == "passed")
        failure = result.get("failure") if isinstance(result.get("failure"), dict) else None
        message = f"{failure.get('phase')}: {failure.get('message')}" if failure else ""
        # 表が崩れないように改行とパイプを潰す
        message = message.replace("|", "\\|").replace("\n", " ")[:200]
        lines.append(
            f"| {run['id']} | {run_version(run) or '?'} | {run['status']} | {passed_steps}/{len(steps)} "
            f"| {len(result.get('screenshots') or [])} | {message} |"
        )
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
    """Actions 上なら、ステップサマリーと action の出力（status / summary）を書く。"""
    step_summary = env.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as f:
            f.write(markdown_summary(manifest))
    github_output = env.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as f:
            f.write(f"status={overall_status(manifest['summary'])}\n")
            f.write(f"summary={json.dumps(manifest['summary'], separators=(',', ':'))}\n")


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
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "generator": {"name": GENERATOR_NAME, "version": generator_version()},
        "generatedAt": utc_now(),
        "title": args.title,
        "ci": ci_from_env(env),
        "summary": summarize(runs),
        "players": union_in_order(runs, "players", "name"),
        "shots": union_in_order(runs, "screenshots", "name", exclude={FAILURE_SHOT}),
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
