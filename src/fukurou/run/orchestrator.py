"""fukurou run の本体。準備 → サーバー起動 → プレイヤー参加 → シナリオ → 後片付け の順に進める。

どの段階で失敗しても、後片付けとログの回収を行ってから result.json を必ず書く。
"""

import logging
from pathlib import Path
import re
import shutil
import sys
import time

from fukurou.dependencies import fetch_dependency, parse_dependencies
from fukurou.errors import FukurouError, InvalidInputError
from fukurou.java import default_java, java_major
from fukurou.mojang import fetch_java_major
from fukurou.net import cached_download
from fukurou.paper import resolve_build
from fukurou.plugins import PluginJar, inspect_plugins, required_java
from fukurou.result.model import FailurePhase, PluginInfo, ScreenshotInfo
from fukurou.result.recorder import ResultRecorder
from fukurou.run.artifacts import HARNESS_LOG, RESULT_FILE, SCREENSHOTS_DIR, ArtifactCollector
from fukurou.run.harness_log import harness_log
from fukurou.run.options import RunOptions
from fukurou.runner.client import ClientProcess
from fukurou.runner.player_session import PlayerSession
from fukurou.runner.process import GameProcessError, read_log, tail_log
from fukurou.runner.scenario_runner import CapturedScreenshot, ScenarioRunner
from fukurou.runner.xvfb import VirtualDisplay
from fukurou.scenario import FAILURE_SCREENSHOT, PlayerSpec, Scenario, ScenarioError
from fukurou.server.plugin_checks import PluginCheckError, PluginIdentity, check_plugins
from fukurou.server.process import ServerProcess
from fukurou.server.server_dir import ServerDirSpec, prepare_server_dir
from fukurou.versions import check_single_version, resolve_version

logger = logging.getLogger(__name__)

EULA_MESSAGE = (
    "fukurou downloads and runs the Minecraft server and clients, which requires accepting the "
    "Minecraft EULA (https://aka.ms/MinecraftEULA). Pass --accept-eula to accept it."
)
# 初回はクライアント本体とアセットのダウンロードを含むため長めに取る
CLIENT_INSTALL_TIMEOUT = 900.0
CLIENT_JOIN_TIMEOUT = 900.0
# Paperclip の展開とワールド生成を含む
SERVER_START_TIMEOUT = 600.0
# 起動完了の時点で有効化は終わっているはずなので短くてよい
PLUGIN_CHECK_TIMEOUT = 15.0
# 失敗時のスクリーンショットでは長時間ウィンドウを待たない
FAILURE_WINDOW_TIMEOUT = 10.0
# 実行ごとに作り直す work-dir 内のディレクトリ（ダウンロード物の tools / cache は残す）
DISPOSABLE_DIRS = ("server", "clients", "logs")
# fukurou が作った work-dir であることを示す印。無い work-dir の既存ディレクトリは利用者のものとみなして消さない
WORK_DIR_MARKER = ".fukurou-work"


class GameTestRun:
    """1つの Minecraft バージョンでシナリオを1回実行する。"""

    def __init__(self, options: RunOptions):
        self.options = options
        self.work_dir = options.work_dir.resolve()
        self.recorder = ResultRecorder(options.minecraft_version)
        self.artifacts = ArtifactCollector(options.out_dir.resolve())
        # 失敗したときに、どの段階の失敗として記録するか
        self.phase: FailurePhase = "setup"
        self.scenario: Scenario | None = None
        # plugins はテスト対象と dependencies の両方、plugins_under_test はテスト対象のみ
        self.plugins: list[PluginJar] = []
        self.plugins_under_test: list[PluginJar] = []
        self.server: ServerProcess | None = None
        self.players: dict[str, PlayerSession] = {}
        self.runner: ScenarioRunner | None = None

    def execute(self) -> int:
        """実行して終了コード（0: 成功 / 1: 失敗・エラー / 2: 入力の誤り）を返す。"""
        try:
            self.artifacts.reset()
        except InvalidInputError as error:
            # 利用者のディレクトリに harness.log を書かないよう、result.json だけを書いて終える
            print(f"fukurou: {error}", file=sys.stderr)
            self.recorder.fail("setup", str(error))
            self.recorder.write(self.artifacts.path(RESULT_FILE))
            return 2
        with harness_log(self.artifacts.path(HARNESS_LOG)):
            self.recorder.logs.append(self.artifacts.harness_log())
            exit_code = self._run_phases()
            result_path = self.artifacts.path(RESULT_FILE)
            try:
                self._teardown()
            finally:
                # 後片付け中に再度中断されても result.json だけは必ず書く
                result = self.recorder.write(result_path)
                logger.info("%s: wrote %s", result.status, result_path)
        # 後片付けで失敗が記録された場合も、成功扱いにはしない
        return exit_code if exit_code or result.status == "passed" else 1

    # --- 各段階 ---------------------------------------------------------------

    def _run_phases(self) -> int:
        try:
            self._setup()
            self.phase = "server-start"
            self._start_server()
            self.phase = "client-join"
            self._join_players()
            self.phase = "scenario"
            self._run_scenario()
        except InvalidInputError as error:
            self._record_failure(str(error))
            return 2
        except FukurouError as error:
            self._record_failure(str(error))
        except Exception as error:  # noqa: BLE001 - どの失敗でも診断情報を残してから終了する
            logger.exception("unexpected error during %s", self.phase)
            self._record_failure(f"{type(error).__name__}: {error}")
        except KeyboardInterrupt:
            # ステップの失敗ではないため、シナリオ中の中断でも failed ではなく error になる（recorder.status）
            self._record_failure("interrupted")
        return 0 if self.recorder.status == "passed" else 1

    def _setup(self) -> None:
        """ネットワークを使わない入力の確認を先に済ませてから、ダウンロードや準備を行う。"""
        options = self.options
        if not options.accept_eula:
            raise InvalidInputError(EULA_MESSAGE)
        self.scenario = self._load_scenario()
        check_single_version(options.minecraft_version)
        self.plugins = inspect_plugins(options.plugins_dir, options.plugins)
        self.plugins_under_test = list(self.plugins)
        self.recorder.plugins = [plugin_info(plugin, "under-test") for plugin in self.plugins]
        dependencies = parse_dependencies(options.dependencies)
        # サーバーはサーバーディレクトリで起動するため、相対パスは今のディレクトリ基準の絶対パスにしておく
        # （resolve ではなく absolute にして、シンボリックリンクの java はそのまま使う）
        java = (options.java or default_java()).absolute()
        self.recorder.java.server = java_major(java)

        version = resolve_version(options.minecraft_version)
        self.recorder.minecraft.version = version
        logger.info("Minecraft %s, Java %s (%s)", version, self.recorder.java.server, java)
        self._reset_work_dir()

        for dependency in dependencies:
            logger.info("downloading dependency %s", dependency.source)
            downloaded = fetch_dependency(dependency, self._dependency_cache)
            jar = PluginJar.inspect(downloaded.path)
            self.plugins.append(jar)
            self.recorder.plugins.append(plugin_info(jar, "dependency", source=downloaded.source))
        self._check_java(version, java, fetch_java_major(version))

        self.server = self._prepare_server(version, java)
        self.players = {spec.name: self._player_session(spec.name, version) for spec in self.scenario.players}
        self.runner = ScenarioRunner(self.server, self.players, self.artifacts.path(SCREENSHOTS_DIR))
        for session in self.players.values():
            logger.info("%s: installing the client", session.name)
            session.client.install(timeout=CLIENT_INSTALL_TIMEOUT)

    def _start_server(self) -> None:
        logger.info("starting the server on port %d", self.server.port)
        try:
            self.server.start(timeout=SERVER_START_TIMEOUT)
        except GameProcessError:
            logger.error("server output (last lines):\n%s", tail_log(self.server.log_path))
            raise
        identities = [
            PluginIdentity(name=plugin.name, file_name=plugin.path.name, log_prefix=plugin.log_prefix)
            for plugin in self.plugins
            if plugin.name
        ]
        names = [identity.name for identity in identities]
        try:
            enabled = check_plugins(lambda: read_log(self.server.log_path), identities, PLUGIN_CHECK_TIMEOUT)
        except PluginCheckError as error:
            self.recorder.set_plugin_enabled(error.enabled)
            raise
        self.recorder.set_plugin_enabled(enabled)
        logger.info("plugins enabled: %s", ", ".join(names) or "none")

    def _join_players(self) -> None:
        """プレイヤーを1人ずつ参加させる。同時に起動すると CPU を取り合い、読み込みが大幅に遅くなるため。"""
        for spec in self.scenario.players:
            self._join(self.players[spec.name], spec)
            self.recorder.mark_joined(spec.name)

    def _run_scenario(self) -> None:
        """ステップを順に実行し、最初に失敗したステップで止める。"""
        for index, step in enumerate(self.scenario.steps):
            logger.info("step %d: %r", index, step)
            started = time.monotonic()
            try:
                captured = self.runner.run_step(step)
            except Exception as error:  # noqa: BLE001 - ステップの失敗はどの例外でもシナリオの失敗として記録する
                if not isinstance(error, FukurouError):
                    logger.exception("step %d raised an unexpected error", index)
                message = str(error) if isinstance(error, FukurouError) else f"{type(error).__name__}: {error}"
                logger.error("step %d failed: %s", index, message)
                self.recorder.step_failed(index, _elapsed_ms(started), message)
                return
            screenshot = None
            if captured is not None:
                screenshot = self._record_screenshot(captured, index)
            self.recorder.step_passed(index, _elapsed_ms(started), screenshot=screenshot)
        logger.info("scenario passed")

    def _teardown(self) -> None:
        """失敗時の画面の保存、プロセスの停止、ログの回収。ここでの失敗が元の失敗を隠さないようにする。"""
        failure = self.recorder.failure
        if failure is not None and failure.phase in ("client-join", "scenario"):
            self._safely("capture failure screenshots", self._capture_failure_screenshots)
        for session in self.players.values():
            self._safely(f"stop {session.name}", session.stop)
        if self.server is not None:
            self._safely("stop the server", self.server.stop)
        self._safely("collect logs", self._collect_logs)

    # --- 準備の詳細 -------------------------------------------------------------

    def _load_scenario(self) -> Scenario:
        """シナリオを読み込む。不正でも名前とハッシュは記録しておく。"""
        try:
            source = self.options.scenario_source()
            self.recorder.scenario = source.info()
            scenario = source.parse()
        except ScenarioError as error:
            raise InvalidInputError(f"invalid scenario: {error}") from error
        self.recorder.set_players(scenario.players)
        self.recorder.set_steps(scenario.steps)
        return scenario

    def _reset_work_dir(self) -> None:
        """前回の server / clients / logs を消す。fukurou が作った work-dir でなければ消さずに止める。"""
        self.work_dir.mkdir(parents=True, exist_ok=True)
        marker = self.work_dir / WORK_DIR_MARKER
        if not marker.exists():
            # actions/cache で tools / cache だけが復元された work-dir は印が無くても安全に使える
            foreign = [name for name in DISPOSABLE_DIRS if (self.work_dir / name).exists()]
            if foreign:
                raise InvalidInputError(
                    f"refusing to delete {', '.join(foreign)} in {self.work_dir}: the directory was not created by "
                    "fukurou; choose an empty or new --work-dir"
                )
            marker.touch()
        for name in DISPOSABLE_DIRS:
            shutil.rmtree(self.work_dir / name, ignore_errors=True)

    @property
    def _dependency_cache(self) -> Path:
        return self.work_dir / "cache" / "dependencies"

    def _check_java(self, version: str, java: Path, minecraft_java: int) -> None:
        """サーバーの Java が Minecraft とプラグインの要求を満たすかを、起動前に確かめる。"""
        required = required_java(minecraft_java, self.plugins)
        actual = self.recorder.java.server
        if actual is None:
            logger.warning("could not determine the Java version of %s; Java %d or later is needed", java, required)
        elif actual < required:
            message = f"Minecraft {version} with these plugins needs Java {required} or later, but {java} is Java {actual}"
            # fukurou java（action の java-version: auto）は dependencies を見ないため、その場合の対処を示す
            if required_java(minecraft_java, self.plugins_under_test) < required:
                message += "; a dependency needs the newer Java, so set the Java version explicitly (java-version in the action)"
            raise InvalidInputError(message)

    def _prepare_server(self, version: str, java: Path) -> ServerProcess:
        """Paper の jar を取得し、まっさらなサーバーディレクトリを用意する。"""
        build = resolve_build(version, self.options.server_build)
        self.recorder.minecraft.build = build.id
        self.recorder.minecraft.channel = build.channel
        download = build.server_download()
        logger.info("Paper %s build %d (%s)", version, build.id, build.channel)
        jar = cached_download(download.url, self.work_dir / "cache" / "paper" / download.name, download.checksums.sha256)
        server = ServerProcess(
            java=java,
            jar=jar,
            server_dir=self.work_dir / "server",
            log_path=self.work_dir / "logs" / "server-console.log",
            bundler_dir=self.work_dir / "cache" / "paper-bundler" / version,
        )
        spec = ServerDirSpec(
            plugin_jars=[plugin.path for plugin in self.plugins],
            properties_text=self.options.server_properties,
            managed_properties=server.managed_properties(max_players=len(self.scenario.players)),
            server_files=self.options.server_files,
            accept_eula=self.options.accept_eula,
        )
        prepare_server_dir(server.server_dir, spec)
        return server

    def _player_session(self, name: str, version: str) -> PlayerSession:
        logs_dir = self.work_dir / "logs"
        client = ClientProcess(
            tools_dir=self.work_dir / "tools",
            # バージョンやアセットのキャッシュは全プレイヤーで共有し、設定・ログはプレイヤーごとに分ける
            cache_dir=self.work_dir / "cache" / "minecraft",
            client_dir=self.work_dir / "clients" / name,
            log_dir=logs_dir,
            minecraft_version=version,
            username=name,
            # PortableMC は tools_dir で動くため、相対パスは今のディレクトリ基準の絶対パスにしておく
            java=self.options.client_java.absolute() if self.options.client_java else None,
        )
        display = VirtualDisplay(logs_dir / f"{name}-xvfb.log")
        return PlayerSession(name=name, client=client, display=display, window_timeout=CLIENT_JOIN_TIMEOUT)

    # --- 参加・記録の詳細 ---------------------------------------------------------

    def _join(self, session: PlayerSession, spec: PlayerSpec) -> None:
        logger.info("%s: starting the client", spec.name)
        session.start(self.server.port)
        joined = re.compile(rf"\b{re.escape(spec.name)} joined the game")
        deadline = time.monotonic() + CLIENT_JOIN_TIMEOUT
        while not joined.search(read_log(self.server.log_path)):
            if time.monotonic() > deadline:
                raise GameProcessError(f"{spec.name} did not join within {CLIENT_JOIN_TIMEOUT:.0f} seconds")
            try:
                session.check_alive()
            except GameProcessError:
                logger.error("%s client output (last lines):\n%s", spec.name, tail_log(session.client.launch_log))
                raise
            time.sleep(1.0)
        logger.info("%s joined", spec.name)
        if spec.op:
            logger.info("%s: %s", spec.name, self.server.command(f"op {spec.name}").strip())

    def _record_screenshot(self, captured: CapturedScreenshot, step_index: int | None) -> str:
        relative = self.artifacts.relative(captured.path)
        self.recorder.add_screenshot(
            ScreenshotInfo(
                player=captured.player,
                name=captured.name,
                path=relative,
                width=captured.width,
                height=captured.height,
                step_index=step_index,
            )
        )
        return relative

    def _capture_failure_screenshots(self) -> None:
        """失敗時の各プレイヤーの画面を残す。ウィンドウが無い等で撮れなければ諦める。"""
        step_index = self.recorder.failure.step_index
        for session in self.players.values():
            # 参加前に失敗した場合など、まだディスプレイが無いプレイヤーは撮れない
            if session.display.name is None:
                continue
            session.window_timeout = FAILURE_WINDOW_TIMEOUT
            try:
                session.check_alive()
                captured = self.runner.capture(session, FAILURE_SCREENSHOT)
            except Exception as error:  # noqa: BLE001 - 診断用の撮影失敗で本来のエラーを隠さない
                logger.warning("%s: could not capture a failure screenshot: %s", session.name, error)
                continue
            self._record_screenshot(captured, step_index)

    def _collect_logs(self) -> None:
        if self.server is not None:
            info = self.artifacts.collect_server_log([self.server.latest_log, self.server.log_path])
            if info is not None:
                self.recorder.logs.append(info)
        for session in self.players.values():
            client = session.client
            info = self.artifacts.collect_client_log(
                session.name, [client.latest_log, client.launch_log, client.install_log]
            )
            if info is not None:
                self.recorder.logs.append(info)
            self.recorder.logs.extend(self.artifacts.collect_crash_reports(session.name, client.crash_reports_dir))

    def _record_failure(self, message: str) -> None:
        logger.error("%s failed: %s", self.phase, message)
        self.recorder.fail(self.phase, message)

    def _safely(self, description: str, action) -> None:
        """後片付けの1手順を実行する。失敗しても次の手順へ進み、先に失敗が無ければ teardown の失敗として残す。"""
        try:
            action()
        except Exception as error:  # noqa: BLE001 - 後片付けは最後まで進める
            logger.exception("could not %s", description)
            self.recorder.fail("teardown", f"could not {description}: {error}")


def plugin_info(plugin: PluginJar, role: str, source: str | None = None) -> PluginInfo:
    """result.json の plugins 欄の1要素を作る。"""
    return PluginInfo(
        file=plugin.path.name,
        sha256=plugin.sha256,
        name=plugin.name,
        version=plugin.version,
        role=role,
        source=source,
        class_file_major=plugin.class_file_major,
    )


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)
