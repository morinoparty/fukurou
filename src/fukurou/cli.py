"""fukurou のコマンドライン。サブコマンドごとに引数を解釈し、各モジュールへ処理を渡す。"""

import argparse
import json
from pathlib import Path
import signal
import sys

from fukurou import __version__
from fukurou.errors import FukurouError, InvalidInputError
from fukurou.schema import SCHEMA_NAMES, schema_json
from fukurou.versions import DEFAULT_MAX_VERSIONS

# 終了コード: 0 成功 / 1 失敗・エラー / 2 入力の誤り
EXIT_OK = 0
EXIT_FAILED = 1
EXIT_INVALID = 2
DEFAULT_PLUGINS = "*.jar"


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


def _add_validate(commands) -> None:
    parser = commands.add_parser("validate", help="Check that a scenario is valid without starting anything.")
    _add_scenario_arguments(parser)
    parser.set_defaults(handler=_validate)


def _add_schema(commands) -> None:
    parser = commands.add_parser("schema", help="Print the JSON Schema of a scenario or of result.json.")
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
    parser = commands.add_parser("run", help="Run a scenario against one Minecraft version and write result.json.")
    parser.add_argument("--minecraft-version", required=True, help="a single Minecraft version, such as 1.21.11")
    _add_scenario_arguments(parser)
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


def _add_scenario_arguments(parser: argparse.ArgumentParser) -> None:
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--scenario-file", type=Path, help="scenario file (JSON or YAML)")
    group.add_argument("--scenario", help="inline scenario text (JSON or YAML)")


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


def _validate(args: argparse.Namespace) -> int:
    from fukurou.scenario import ScenarioError, ScenarioSource

    try:
        source = ScenarioSource.from_file(args.scenario_file) if args.scenario_file else ScenarioSource.inline(args.scenario)
        scenario = source.parse()
    except ScenarioError as error:
        return _fail(EXIT_INVALID, f"invalid scenario: {error}")
    print(f"{source.name}: valid (players: {len(scenario.players)}, steps: {len(scenario.steps)})")
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
    from fukurou.run.orchestrator import GameTestRun

    options = RunOptions(
        minecraft_version=args.minecraft_version,
        scenario_file=args.scenario_file,
        scenario_text=args.scenario,
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
    # ジョブのキャンセル（SIGTERM）でも Ctrl+C と同じく後片付けと result.json の書き出しを行う
    signal.signal(signal.SIGTERM, _interrupt)
    return GameTestRun(options).execute()


def _interrupt(signum, frame) -> None:
    # 後片付け中に再度届いても中断しないよう、以降の SIGTERM は無視する
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    raise KeyboardInterrupt


def _fail(code: int, message: str) -> int:
    print(f"fukurou: {message}", file=sys.stderr)
    return code


if __name__ == "__main__":
    sys.exit(main())
