"""fukurou のコマンドライン。サブコマンドごとに引数を解釈し、各モジュールへ処理を渡す。"""

import argparse
import json
from pathlib import Path
import re
import signal
import sys
from typing import TYPE_CHECKING

from fukurou import __version__
from fukurou.errors import FukurouError, InvalidInputError
from fukurou.schema import SCHEMA_NAMES, schema_json
from fukurou.versions import DEFAULT_MAX_VERSIONS

if TYPE_CHECKING:
    from fukurou.scenario import Selection

# 終了コード: 0 成功 / 1 失敗・エラー / 2 入力の誤り
EXIT_OK = 0
EXIT_FAILED = 1
EXIT_INVALID = 2
DEFAULT_PLUGINS = "*.jar"
ISOLATIONS = ("reset", "fresh-server")


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.handler(args)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="fukurou",
        description="In-game test runner for Minecraft (Paper) plugins: drive real vanilla clients from a scenario.",
    )
    parser.add_argument("--version", action="version", version=f"fukurou {__version__}")
    commands = parser.add_subparsers(dest="command", required=True, metavar="COMMAND")
    _add_versions(commands)
    _add_list(commands)
    _add_validate(commands)
    _add_schema(commands)
    _add_java(commands)
    _add_run(commands)
    return parser


# --- サブコマンドの定義 -----------------------------------------------------------


def _add_versions(commands) -> None:
    parser = commands.add_parser("versions", help="Resolve a version spec to a JSON array of Minecraft versions.")
    parser.add_argument("spec", help='"latest", "1.21.11", "1.21.6-" or "1.20.5-1.21.11" (Minecraft 1.20 or later)')
    parser.add_argument(
        "--max-versions",
        type=int,
        default=DEFAULT_MAX_VERSIONS,
        help=f"fail when the spec resolves to more versions than this (default {DEFAULT_MAX_VERSIONS})",
    )
    parser.set_defaults(handler=_versions)


def _add_list(commands) -> None:
    parser = commands.add_parser("list", help="Print the selected test ids in run order as a JSON array.")
    _add_selection_arguments(parser)
    parser.add_argument(
        "--minecraft-version",
        help="also report (on stderr) the tests that versions: skips on this single Minecraft version",
    )
    parser.set_defaults(handler=_list)


def _add_validate(commands) -> None:
    parser = commands.add_parser(
        "validate", help="Check that the suite and the selected tests are valid without starting anything."
    )
    _add_selection_arguments(parser)
    parser.set_defaults(handler=_validate)


def _add_schema(commands) -> None:
    parser = commands.add_parser("schema", help="Print the JSON Schema of a scenario, a suite or result.json.")
    parser.add_argument("name", choices=SCHEMA_NAMES)
    parser.set_defaults(handler=_schema)


def _add_java(commands) -> None:
    parser = commands.add_parser(
        "java",
        help="Print the Java major version for the server: the newer of what Mojang requires and what the plugins need.",
    )
    parser.add_argument("--minecraft-version", required=True, help="a single Minecraft version, such as 1.21.11")
    _add_plugin_arguments(parser)
    parser.set_defaults(handler=_java)


def _add_run(commands) -> None:
    parser = commands.add_parser(
        "run", help="Run the selected tests against one Minecraft version in one server session and write result.json."
    )
    parser.add_argument("--minecraft-version", required=True, help="a single Minecraft version, such as 1.21.11")
    _add_selection_arguments(parser)
    parser.add_argument("--fail-fast", action="store_true", help="stop the suite at the first failed test")
    parser.add_argument(
        "--accept-eula",
        action="store_true",
        help="accept the Minecraft EULA (https://aka.ms/MinecraftEULA); required to start the server and clients",
    )
    _add_plugin_arguments(parser)
    parser.add_argument(
        "--dependencies",
        default="",
        help="YAML list of extra plugins to download: {url, sha256?} or {github: owner/repo, tag, asset}",
    )
    parser.add_argument("--server-properties", default="", help='extra server.properties lines ("key=value", one per line)')
    parser.add_argument("--server-files", type=Path, help="directory whose contents are copied into the server directory")
    parser.add_argument("--server-build", type=int, help="Paper build number (default: the latest stable build)")
    parser.add_argument("--java", type=Path, help="java for the server (default: $JAVA_HOME/bin/java or java on PATH)")
    parser.add_argument(
        "--client-java", type=Path, help="java for the clients (default: the Mojang runtime that PortableMC installs)"
    )
    parser.add_argument("--work-dir", type=Path, default=Path(".fukurou-work"), help="work directory (default ./.fukurou-work)")
    parser.add_argument("--out-dir", type=Path, default=Path("fukurou-out"), help="output directory (default ./fukurou-out)")
    parser.set_defaults(handler=_run)


def _add_selection_arguments(parser: argparse.ArgumentParser) -> None:
    # どれも組み合わせられる。1 つも無ければ discover_tests が入力の誤り（exit 2）にする
    sources = parser.add_argument_group("test selection (at least one of --suite, --scenarios, --scenario-file, --scenario)")
    sources.add_argument("--suite", type=Path, help="suite file (fukurou.yml); its scenarios globs are relative to it")
    sources.add_argument(
        "--scenarios",
        action="append",
        metavar="GLOB",
        help="glob of scenario files relative to the cwd (repeatable; commas and newlines also separate)",
    )
    sources.add_argument(
        "--scenario-file",
        action="append",
        metavar="PATH",
        help="scenario file, JSON or YAML (repeatable; newlines also separate)",
    )
    sources.add_argument("--scenario", metavar="TEXT", help="inline scenario text, JSON or YAML (test id 'inline')")
    filters = parser.add_argument_group("filters (both given: a test must match both)")
    filters.add_argument(
        "--test",
        action="append",
        metavar="ID_OR_GLOB",
        help="run only tests whose id matches (repeatable; commas and newlines also separate)",
    )
    filters.add_argument(
        "--tag",
        action="append",
        metavar="TAG",
        help="run only tests with one of these tags (repeatable; commas and newlines also separate)",
    )
    filters.add_argument("--isolation", choices=ISOLATIONS, help="force this isolation for every test")


def _add_plugin_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--plugins-dir", type=Path, default=Path("."), help="directory that contains the plugin jars (default: cwd)"
    )
    parser.add_argument(
        "--plugins",
        default=DEFAULT_PLUGINS,
        help=f'glob patterns inside --plugins-dir, separated by newlines or commas (default "{DEFAULT_PLUGINS}"; "" for none)',
    )


# --- サブコマンドの処理 -----------------------------------------------------------


def _versions(args: argparse.Namespace) -> int:
    from fukurou.versions import resolve_versions

    if args.max_versions < 1:
        return _fail(EXIT_INVALID, "--max-versions must be at least 1")
    try:
        print(json.dumps(resolve_versions(args.spec, args.max_versions)))
    except InvalidInputError as error:
        return _fail(EXIT_INVALID, f"invalid version spec: {error}")
    except FukurouError as error:
        return _fail(EXIT_FAILED, f"could not resolve versions: {error}")
    return EXIT_OK


def _list(args: argparse.Namespace) -> int:
    from fukurou.scenario import discover_tests

    try:
        _, tests = discover_tests(selection_from_args(args))
        skipped = _versions_skips(tests, args.minecraft_version) if args.minecraft_version else {}
    except InvalidInputError as error:
        return _fail(EXIT_INVALID, str(error))
    except FukurouError as error:
        return _fail(EXIT_FAILED, f"could not resolve versions: {error}")
    # stdout は機械が読む（アクションの早期検証）ので id の配列だけにし、versions: による skip は stderr に出す
    for test_id, reason in skipped.items():
        print(f"fukurou: {test_id} will be skipped: {reason}", file=sys.stderr)
    print(json.dumps([test.id for test in tests]))
    return EXIT_OK


def _versions_skips(tests, minecraft_version: str) -> dict[str, str]:
    """versions: で除外されるテストの id と理由。Mojang のリリース一覧を 1 回だけ取得する。"""
    from fukurou.mojang import fetch_manifest
    from fukurou.versions import check_single_version

    version = check_single_version(minecraft_version)
    releases = fetch_manifest().release_ids()
    reasons = {test.id: test.skip_reason_for(version, releases) for test in tests}
    return {test_id: reason for test_id, reason in reasons.items() if reason is not None}


def _validate(args: argparse.Namespace) -> int:
    from fukurou.scenario import inspect_tests

    try:
        discovery = inspect_tests(selection_from_args(args))
    except InvalidInputError as error:
        return _fail(EXIT_INVALID, str(error))
    # 有効なテストは実行順に 1 行ずつ、問題は stderr に 1 行ずつ出す
    for test in discovery.tests:
        steps = sum(1 for step in test.steps if step.phase == "test")
        print(
            f"{test.id}: valid (players: {len(test.players)}, steps: {steps}, "
            f"planned steps: {len(test.steps)}, isolation: {test.isolation})"
        )
    for error in discovery.errors:
        print(f"fukurou: {error}", file=sys.stderr)
    if discovery.errors:
        return EXIT_INVALID
    if not discovery.tests:
        return _fail(EXIT_INVALID, "no tests were selected")
    return EXIT_OK


def _schema(args: argparse.Namespace) -> int:
    sys.stdout.write(schema_json(args.name))
    return EXIT_OK


def _java(args: argparse.Namespace) -> int:
    from fukurou.mojang import fetch_java_major
    from fukurou.plugins import inspect_plugins, required_java

    try:
        plugins = inspect_plugins(args.plugins_dir, args.plugins)
        print(required_java(fetch_java_major(args.minecraft_version), plugins))
    except InvalidInputError as error:
        return _fail(EXIT_INVALID, str(error))
    except FukurouError as error:
        return _fail(EXIT_FAILED, str(error))
    return EXIT_OK


def _run(args: argparse.Namespace) -> int:
    from fukurou.run.options import RunOptions

    options = RunOptions(
        minecraft_version=args.minecraft_version,
        selection=selection_from_args(args),
        fail_fast=args.fail_fast,
        accept_eula=args.accept_eula,
        plugins_dir=args.plugins_dir,
        plugins=args.plugins,
        dependencies=args.dependencies,
        server_properties=args.server_properties,
        server_files=args.server_files,
        server_build=args.server_build,
        java=args.java,
        client_java=args.client_java,
        work_dir=args.work_dir,
        out_dir=args.out_dir,
    )
    # 実行系（サーバー・クライアント）は run のときだけ読み込む
    from fukurou.run.suite_run import SuiteRun

    # ジョブのキャンセル（SIGTERM）でも Ctrl+C と同じく後片付けと result.json の書き出しを行う
    signal.signal(signal.SIGTERM, _interrupt)
    return SuiteRun(options).execute()


def selection_from_args(args: argparse.Namespace) -> "Selection":
    """list / validate / run で共通の選択引数を Selection にする。"""
    from fukurou.scenario import Selection

    return Selection(
        suite=args.suite,
        scenario_files=[Path(path) for path in split_values(args.scenario_file, commas=False)],
        scenario_globs=split_values(args.scenarios),
        scenario_text=args.scenario,
        test_filters=split_values(args.test),
        tag_filters=split_values(args.tag),
        isolation=args.isolation,
    )


def split_values(values: list[str] | None, commas: bool = True) -> list[str]:
    """繰り返し指定された引数を改行（と既定ではカンマ）でさらに分け、空要素を除く。

    アクションの入力（"a, b" や複数行）をそのまま 1 つの引数として渡せるようにするためのもの。
    """
    separator = r"[,\n]" if commas else r"\n"
    return [item.strip() for value in values or [] for item in re.split(separator, value) if item.strip()]


def _interrupt(signum, frame) -> None:
    # 後片付け中に再度届いても中断しないよう、以降の SIGTERM は無視する
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    raise KeyboardInterrupt


def _fail(code: int, message: str) -> int:
    print(f"fukurou: {message}", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
