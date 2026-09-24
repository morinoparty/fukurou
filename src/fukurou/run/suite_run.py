"""fukurou run の本体。1 バージョン = 1 プロセス = 1 サーバーセッションで、選択した全テストを順に実行する。

流れ: setup（1 回）→ session 0 の起動と全員の参加 → テストごとに（必要なら fresh-server / クライアントの再起動 →）
リセット → ログのマーク → beforeEach / fixture / steps → result.json の書き直し → teardown（1 回）。

どの段階で失敗しても後片付けとログの回収を行い、result.json は発見直後・各テストの後・後片付けで必ず書く。
ネットワークと外部プロセスに触る部分は Platform にまとめ、テストでは偽物に差し替える。
"""

from dataclasses import dataclass
import logging
from pathlib import Path
import shutil
import sys
import time
from typing import Callable, Protocol

from fukurou.dependencies import DownloadedDependency, GithubDependency, UrlDependency, fetch_dependency, parse_dependencies
from fukurou.errors import FukurouError, InvalidInputError
from fukurou.java import default_java, java_major
from fukurou.mojang import fetch_java_major, fetch_manifest
from fukurou.net import cached_download
from fukurou.paper import fetch_project, latest_channel, resolve_build
from fukurou.plugins import PluginJar, inspect_plugins, required_java
from fukurou.result.model import ArenaInfo, PluginInfo, ResetInfo, ScreenshotInfo, SelectionInfo, SuiteInfo
from fukurou.result.recorder import NOT_RUN, RunRecorder, TestRecorder
from fukurou.run.artifacts import HARNESS_LOG, RESULT_FILE, ArtifactCollector
from fukurou.run.harness_log import harness_log
from fukurou.run.options import RunOptions
from fukurou.run.session import CLIENT_JOIN_TIMEOUT, GameSession
from fukurou.runner.client import ClientDiedError, ClientProcess
from fukurou.runner.player_session import PlayerSession
from fukurou.runner.scenario_runner import CapturedScreenshot, ScenarioRunner
from fukurou.runner.xvfb import VirtualDisplay
from fukurou.scenario import FAILURE_SCREENSHOT, ResetSpec, Suite, TestSpec, discover_tests
from fukurou.server.plugin_checks import PluginIdentity
from fukurou.server.process import ServerProcess, ServerUnavailableError
from fukurou.server.server_dir import ServerDirSpec, prepare_server_dir
from fukurou.versions import check_single_version, select_versions

logger = logging.getLogger(__name__)

EULA_MESSAGE = (
    "fukurou downloads and runs the Minecraft server and clients, which requires accepting the "
    "Minecraft EULA (https://aka.ms/MinecraftEULA). Pass --accept-eula to accept it."
)
# 初回はクライアント本体とアセットのダウンロードを含むため長めに取る
CLIENT_INSTALL_TIMEOUT = 900.0
# 失敗時のスクリーンショットでは長時間ウィンドウを待たない
FAILURE_WINDOW_TIMEOUT = 10.0
# 実行ごとに作り直す work-dir 内のディレクトリ（ダウンロード物の tools / cache は残す）。ジョブに 1 回だけ消す
DISPOSABLE_DIRS = ("server", "clients", "logs")
# fukurou が作った work-dir であることを示す印。無い work-dir の既存ディレクトリは利用者のものとみなして消さない
WORK_DIR_MARKER = ".fukurou-work"
# skipReason の定型
SKIP_FAIL_FAST = "fail-fast"
SKIP_INTERRUPTED = "interrupted"


@dataclass(frozen=True)
class ResolvedVersion:
    """Mojang と Paper に問い合わせて確定したバージョン。releases は versions: の判定に使い回す。"""

    version: str
    # Mojang のリリース一覧（新しい順）
    releases: list[str]
    # そのバージョンの公式サーバーが要求する Java の major 番号
    minecraft_java: int


@dataclass(frozen=True)
class PaperJar:
    path: Path
    build: int
    channel: str


class Platform(Protocol):
    """ネットワークと外部プロセス（サーバー・クライアント・Xvfb）に触る処理。テストでは偽物に差し替える。"""

    def resolve(self, spec: str) -> ResolvedVersion: ...

    def java_major(self, java: Path) -> int | None: ...

    def fetch_dependency(self, dependency: UrlDependency | GithubDependency, cache_dir: Path) -> DownloadedDependency: ...

    def paper(self, version: str, build: int | None, cache_dir: Path) -> PaperJar: ...

    def new_server(self, java: Path, jar: Path, server_dir: Path, log_path: Path, bundler_dir: Path) -> ServerProcess: ...

    def new_player(self, name: str, version: str) -> PlayerSession: ...


class RealPlatform:
    """本物の Minecraft を使う Platform。"""

    def __init__(self, options: RunOptions, work_dir: Path):
        self.options = options
        self.work_dir = work_dir

    def resolve(self, spec: str) -> ResolvedVersion:
        # マニフェストは 1 回だけ取得し、versions: の判定と Java の要求の両方に使う
        manifest = fetch_manifest()
        releases = manifest.release_ids()
        versions = select_versions(spec, releases, fetch_project().version_ids(), latest_channel)
        if len(versions) != 1:
            raise InvalidInputError(f"{spec} must resolve to exactly one version, got {', '.join(versions)}")
        version = versions[0]
        return ResolvedVersion(version=version, releases=releases, minecraft_java=fetch_java_major(version, manifest))

    def java_major(self, java: Path) -> int | None:
        return java_major(java)

    def fetch_dependency(self, dependency: UrlDependency | GithubDependency, cache_dir: Path) -> DownloadedDependency:
        return fetch_dependency(dependency, cache_dir)

    def paper(self, version: str, build: int | None, cache_dir: Path) -> PaperJar:
        resolved = resolve_build(version, build)
        download = resolved.server_download()
        logger.info("Paper %s build %d (%s)", version, resolved.id, resolved.channel)
        jar = cached_download(download.url, cache_dir / download.name, download.checksums.sha256)
        return PaperJar(path=jar, build=resolved.id, channel=resolved.channel)

    def new_server(self, java: Path, jar: Path, server_dir: Path, log_path: Path, bundler_dir: Path) -> ServerProcess:
        return ServerProcess(java=java, jar=jar, server_dir=server_dir, log_path=log_path, bundler_dir=bundler_dir)

    def new_player(self, name: str, version: str) -> PlayerSession:
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


class ServerDied(FukurouError):
    """テストの途中でサーバーが死んだ（RCON 不通またはプロセス終了）。run の失敗（phase server）にする。"""


class RelaunchFailed(FukurouError):
    """クライアントの再起動に失敗した。run の失敗（phase client-join）にする。"""

    def __init__(self, player: str, cause: Exception):
        super().__init__(f"client relaunch failed: {player}: {cause}")
        self.player = player


class SuiteRun:
    """1 つの Minecraft バージョンで、選択した全テストを 1 つのサーバーセッションで実行する。"""

    def __init__(self, options: RunOptions, platform: Platform | None = None):
        self.options = options
        self.work_dir = options.work_dir.resolve()
        self.platform: Platform = platform or RealPlatform(options, self.work_dir)
        self.recorder = RunRecorder(options.minecraft_version)
        self.artifacts = ArtifactCollector(options.out_dir.resolve())
        self.suite: Suite | None = None
        self.tests: list[TestSpec] = []
        # 全テストのプレイヤーの和集合（参加順）
        self.player_names: list[str] = []
        # plugins はテスト対象と dependencies の両方、plugins_under_test はテスト対象のみ
        self.plugins: list[PluginJar] = []
        self.plugins_under_test: list[PluginJar] = []
        self.session: GameSession | None = None
        # 現在の run の失敗の段階（セッションの外で失敗したときに使う）
        self.phase = "setup"

    def execute(self) -> int:
        """実行して終了コード（0: 全テストが passed / skipped、1: failed / error、2: 入力の誤り）を返す。"""
        try:
            self.artifacts.reset()
        except InvalidInputError as error:
            # 利用者のディレクトリに harness.log を書かないよう、result.json だけを書いて終える
            print(f"fukurou: {error}", file=sys.stderr)
            self.recorder.fail("setup", str(error))
            self.recorder.write(self.artifacts.path(RESULT_FILE))
            return 2
        result_path = self.artifacts.path(RESULT_FILE)
        with harness_log(self.artifacts.path(HARNESS_LOG)):
            self.recorder.logs.append(self.artifacts.harness_log())
            exit_code = self._run_phases()
            try:
                self._teardown()
            finally:
                # 後片付け中に再度中断されても result.json だけは必ず書く
                result = self.recorder.write(result_path)
                logger.info("%s: wrote %s (%s)", result.status, result_path, _summary_text(result))
        # 後片付けで失敗が記録された場合も、成功扱いにはしない
        return exit_code if exit_code or result.status == "passed" else 1

    # --- 各段階 ---------------------------------------------------------------

    def _run_phases(self) -> int:
        try:
            self._setup()
            self.phase = "server-start"
            self._start_session()
            self.phase = "server"
            self._run_tests()
        except InvalidInputError as error:
            self._record_failure(str(error))
            return 2
        except FukurouError as error:
            self._record_failure(str(error))
        except KeyboardInterrupt:
            self._interrupted()
        except Exception as error:  # noqa: BLE001 - どの失敗でも診断情報を残してから終了する
            logger.exception("unexpected error during %s", self.phase)
            self._record_failure(f"{type(error).__name__}: {error}")
        return 0 if self.recorder.status == "passed" else 1

    def _setup(self) -> None:
        """ネットワークを使わない入力の確認を先に済ませ、テストの発見直後にスタブの result.json を書いてから準備する。"""
        options = self.options
        if not options.accept_eula:
            raise InvalidInputError(EULA_MESSAGE)
        self.suite, self.tests = discover_tests(options.selection)
        self._register_tests()
        # ジョブが途中で消えても「発見はできたが走らなかった」と分かるよう、ここで最初の result.json を書く
        self.recorder.write(self.artifacts.path(RESULT_FILE))
        check_single_version(options.minecraft_version)
        self.plugins = inspect_plugins(options.plugins_dir, options.plugins)
        self.plugins_under_test = list(self.plugins)
        self.recorder.plugins = [plugin_info(plugin, "under-test") for plugin in self.plugins]
        dependencies = parse_dependencies(options.dependencies)
        # サーバーはサーバーディレクトリで起動するため、相対パスは今のディレクトリ基準の絶対パスにしておく
        # （resolve ではなく absolute にして、シンボリックリンクの java はそのまま使う）
        java = (options.java or default_java()).absolute()
        self.recorder.java.server = self.platform.java_major(java)

        resolved = self.platform.resolve(options.minecraft_version)
        self.recorder.minecraft.version = resolved.version
        logger.info("Minecraft %s, Java %s (%s)", resolved.version, self.recorder.java.server, java)
        self._skip_by_versions(resolved)
        self._reset_work_dir()

        for dependency in dependencies:
            logger.info("downloading dependency %s", dependency.source)
            downloaded = self.platform.fetch_dependency(dependency, self.work_dir / "cache" / "dependencies")
            jar = PluginJar.inspect(downloaded.path)
            self.plugins.append(jar)
            self.recorder.plugins.append(plugin_info(jar, "dependency", source=downloaded.source))
        self._check_java(resolved, java)

        paper = self.platform.paper(resolved.version, options.server_build, self.work_dir / "cache" / "paper")
        self.recorder.minecraft.build = paper.build
        self.recorder.minecraft.channel = paper.channel
        players = {name: self.platform.new_player(name, resolved.version) for name in self.player_names}
        self.session = GameSession(
            server_factory=self._server_factory(java, paper.path, resolved.version),
            players=players,
            join_order=list(self.player_names),
            plugins=self._plugin_identities(),
            artifacts=self.artifacts,
            recorder=self.recorder,
            reset_spec=ResetSpec.of(self.suite),
        )
        for player in players.values():
            logger.info("%s: installing the client", player.name)
            player.install(timeout=CLIENT_INSTALL_TIMEOUT)

    def _start_session(self) -> None:
        self.session.start()
        self.phase = "client-join"
        self.session.join_all()

    def _run_tests(self) -> None:
        """テストを order の順に実行する。各テストの後に result.json を書き直す。"""
        result_path = self.artifacts.path(RESULT_FILE)
        for test in self.tests:
            recorder = self.recorder.test(test.id)
            if recorder.skip_reason is not None and recorder.skip_reason != NOT_RUN:
                # versions: などで既に飛ばすと決まっている
                logger.info("%s: skipped (%s)", test.id, recorder.skip_reason)
                continue
            try:
                self._run_test(test, recorder)
            except ServerDied as error:
                message = f"server died during {test.id}: {error}"
                logger.error(message)
                self.recorder.fail("server", message)
                self.recorder.finish_session(self.session.index, [], failure=str(error))
                self.recorder.skip_pending(f"server died during {test.id}")
                break
            except RelaunchFailed as error:
                logger.error(str(error))
                self.recorder.fail("client-join", str(error))
                self.recorder.skip_pending(f"client relaunch failed: {error.player}")
                break
            except (FukurouError, KeyboardInterrupt):
                # fresh-server の切り替えなど、テストの外での失敗。呼び出し側が run の失敗にする
                raise
            finally:
                self.recorder.write(result_path)
            if self.options.fail_fast and recorder.status in ("failed", "error"):
                logger.info("fail-fast: skipping the remaining tests")
                self.recorder.skip_pending(SKIP_FAIL_FAST)
                self.recorder.write(result_path)
                break

    def _run_test(self, test: TestSpec, recorder: TestRecorder) -> None:
        """テスト 1 件: セッションの用意 → リセット → ステップ → ログ範囲の記録。"""
        session = self.session
        names = [player.name for player in test.players]
        # まだテストを走らせていないセッション（起動直後の session 0）はそのままで新品なので、再起動しない
        if test.isolation == "fresh-server" and self.recorder.sessions[session.index].tests:
            session.restart_fresh(on_phase=self._set_phase)
            self.phase = "server"
        for name in names:
            reason = session.needs_relaunch(name)
            if reason is not None:
                logger.info("%s: relaunching the client (%s)", name, reason)
                try:
                    session.relaunch(name)
                except (FukurouError, OSError) as error:
                    raise RelaunchFailed(name, error) from error

        logger.info("test %s (%d/%d) starting", test.id, test.order + 1, len(self.tests))
        recorder.start(session.index)
        self.recorder.sessions[session.index].tests.append(test.id)
        # logRanges はリセットのコマンドへの応答から始める（リセットの失敗の手掛かりになる）。
        # ステップの照合はリセットより後の行だけを見る（GameSession.reset() の終わりで skip() する）
        session.mark_logs(names)
        runner = ScenarioRunner(
            server=session.server,
            server_log=session.server_log(),
            players={name: session.players[name] for name in names},
            player_logs=session.player_logs(names),
            screenshots_dir=self.artifacts.screenshots_dir(test.id),
        )
        try:
            self._reset_and_run(test, recorder, runner)
        except KeyboardInterrupt:
            # 走り切れなかったテスト（失敗時の撮影の途中も含む）は契約どおり skipped にし、途中の記録は残さない
            self._abandon_test(test, recorder, SKIP_INTERRUPTED)
            raise
        except ServerDied:
            # テストの記録は _reset_and_run が閉じている。呼び出し側が run の失敗にする
            raise
        except Exception as error:
            # ハーネス自身の不具合。テストの結果としては残さず、run の失敗として上げる
            self._abandon_test(test, recorder, f"harness error: {type(error).__name__}: {error}")
            raise

    def _reset_and_run(self, test: TestSpec, recorder: TestRecorder, runner: ScenarioRunner) -> None:
        """リセット → ステップ → テストを閉じる。サーバーが死んでいれば記録を閉じてから ServerDied を上げる。"""
        try:
            try:
                recorder.set_reset(self.session.reset(test))
            except ServerUnavailableError as error:
                recorder.set_reset(ResetInfo(error=str(error)))
                raise ServerDied(str(error)) from error
            if recorder.failure is None:
                self._run_steps(test, recorder, runner)
        except ServerDied:
            self._finish_test(test, recorder, runner, check_server=False)
            raise
        self._finish_test(test, recorder, runner)

    def _abandon_test(self, test: TestSpec, recorder: TestRecorder, reason: str) -> None:
        """走り切れなかったテストを skipped にし、セッションの実行済み一覧からも外す。"""
        recorder.skip(reason)
        session_tests = self.recorder.sessions[self.session.index].tests
        if test.id in session_tests:
            session_tests.remove(test.id)

    def _run_steps(self, test: TestSpec, recorder: TestRecorder, runner: ScenarioRunner) -> None:
        """beforeEach → fixtures → steps を順に実行し、最初の失敗で止める。残りは skipped。"""
        for index, planned in enumerate(test.steps):
            if planned.skip_reason is not None:
                recorder.step_skipped(index, planned.skip_reason)
                continue
            if recorder.elapsed_seconds > test.timeout:
                recorder.fail("timeout", f"the test exceeded its timeout of {test.timeout:g}s before step {index}")
                break
            logger.info("step %d (%s): %r", index, planned.phase, planned.step)
            started = time.monotonic()
            try:
                captured = runner.run_step(planned.step)
            except ClientDiedError as error:
                logger.error("step %d: %s", index, error)
                recorder.step_failed(index, _elapsed_ms(started), str(error), phase="client")
                break
            except ServerUnavailableError as error:
                recorder.step_failed(index, _elapsed_ms(started), str(error))
                raise ServerDied(str(error)) from error
            except KeyboardInterrupt:
                raise
            except Exception as error:  # noqa: BLE001 - ステップの失敗はどの例外でもテストの失敗として記録する
                if not isinstance(error, FukurouError):
                    logger.exception("step %d raised an unexpected error", index)
                message = str(error) if isinstance(error, FukurouError) else f"{type(error).__name__}: {error}"
                # 入力や撮影の失敗（xdotool の失敗、スクリーンショットが出ない）は、クライアントが死んだ結果のことがある。
                # その場合はプラグインの失敗ではなくクライアントの死亡（error / client）として記録する
                died = _dead_client(runner)
                if died is not None:
                    logger.error("step %d: %s (after: %s)", index, died, message)
                    recorder.step_failed(index, _elapsed_ms(started), str(died), phase="client")
                    break
                logger.error("step %d failed: %s", index, message)
                recorder.step_failed(index, _elapsed_ms(started), message)
                break
            screenshot = self._record_screenshot(recorder, captured, index) if captured is not None else None
            recorder.step_passed(index, _elapsed_ms(started), screenshot=screenshot)
        if recorder.failure is None:
            logger.info("test %s passed", test.id)

    def _finish_test(self, test: TestSpec, recorder: TestRecorder, runner: ScenarioRunner, check_server: bool = True) -> None:
        """失敗時の画面を残し、ログ範囲を記録して、テストを閉じる。サーバーが死んでいれば ServerDied にする。"""
        if recorder.failure is not None:
            logger.error("test %s %s: %s", test.id, recorder.status, recorder.failure.message)
            self._capture_failure_screenshots(recorder, runner)
            # 画面が開いたままかもしれないプレイヤーは、次に使う前に再起動する
            self.session.mark_dirty(runner.touched)
        recorder.set_log_ranges(self.session.log_ranges())
        recorder.finish()
        if check_server and not self.session.server_alive():
            raise ServerDied("server exited during the test")

    def _set_phase(self, phase: str) -> None:
        self.phase = phase

    def _teardown(self) -> None:
        """プロセスの停止とログの回収。ここでの失敗が元の失敗を隠さないようにする。"""
        if self.session is None:
            return
        try:
            self.session.stop()
        except Exception as error:  # noqa: BLE001 - 後片付けの失敗は teardown の失敗として残す
            self.recorder.fail("teardown", f"could not stop the session: {error}")

    # --- 準備の詳細 -------------------------------------------------------------

    def _register_tests(self) -> None:
        """発見したテストとスイートの情報を recorder に写し、参加させるプレイヤーの和集合を決める。"""
        selection = self.options.selection
        self.recorder.register_tests(self.tests)
        reset_spec = ResetSpec.of(self.suite)
        arena = reset_spec.arena
        self.recorder.suite = SuiteInfo(
            source=self.suite.source if self.suite is not None else None,
            sha256=self.suite.sha256 if self.suite is not None else None,
            isolation=self.suite.isolation if self.suite is not None else "reset",
            settle=reset_spec.settle,
            gamemode=reset_spec.gamemode,
            arena=False if arena is False else ArenaInfo(size=arena.size, height=arena.height),
        )
        self.recorder.selection = SelectionInfo(
            tests=list(selection.test_filters),
            tags=list(selection.tag_filters),
            isolation=selection.isolation,
            fail_fast=self.options.fail_fast,
        )
        # スイートの宣言順を先にし、テストにしか居ないプレイヤーは現れた順に続ける
        names: list[str] = []
        declared = [player.name for player in self.suite.players] if self.suite is not None else []
        for name in declared + [player.name for test in self.tests for player in test.players]:
            if name not in names:
                names.append(name)
        self.player_names = names
        self.recorder.set_players(names)

    def _skip_by_versions(self, resolved: ResolvedVersion) -> None:
        """versions: が実行中のバージョンを含まないテストを skipped にする（選択には含めたまま）。"""
        for test in self.tests:
            reason = test.skip_reason_for(resolved.version, resolved.releases)
            if reason is not None:
                logger.info("%s: skipped (%s)", test.id, reason)
                self.recorder.test(test.id).skip(reason)

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

    def _check_java(self, resolved: ResolvedVersion, java: Path) -> None:
        """サーバーの Java が Minecraft とプラグインの要求を満たすかを、起動前に確かめる。"""
        required = required_java(resolved.minecraft_java, self.plugins)
        actual = self.recorder.java.server
        if actual is None:
            logger.warning("could not determine the Java version of %s; Java %d or later is needed", java, required)
        elif actual < required:
            message = (
                f"Minecraft {resolved.version} with these plugins needs Java {required} or later, "
                f"but {java} is Java {actual}"
            )
            # fukurou java（action の java-version: auto）は dependencies を見ないため、その場合の対処を示す
            if required_java(resolved.minecraft_java, self.plugins_under_test) < required:
                message += "; a dependency needs the newer Java, so set the Java version explicitly (java-version in the action)"
            raise InvalidInputError(message)

    def _server_factory(self, java: Path, jar: Path, version: str) -> Callable[[int], ServerProcess]:
        """セッション index 用の ServerProcess を作り、まっさらなサーバーディレクトリを用意する関数を返す。"""

        def factory(index: int) -> ServerProcess:
            server = self.platform.new_server(
                java=java,
                jar=jar,
                server_dir=self.work_dir / "server",
                # start_process はログを "wb" で開くため、セッションごとに別のファイルにする
                log_path=self.work_dir / "logs" / "sessions" / str(index) / "server-console.log",
                bundler_dir=self.work_dir / "cache" / "paper-bundler" / version,
            )
            spec = ServerDirSpec(
                plugin_jars=[plugin.path for plugin in self.plugins],
                properties_text=self.options.server_properties,
                managed_properties=server.managed_properties(max_players=len(self.player_names)),
                server_files=self.options.server_files,
                accept_eula=self.options.accept_eula,
            )
            prepare_server_dir(server.server_dir, spec)
            return server

        return factory

    def _plugin_identities(self) -> list[PluginIdentity]:
        return [
            PluginIdentity(name=plugin.name, file_name=plugin.path.name, log_prefix=plugin.log_prefix)
            for plugin in self.plugins
            if plugin.name
        ]

    # --- 記録の詳細 ---------------------------------------------------------------

    def _record_screenshot(self, recorder: TestRecorder, captured: CapturedScreenshot, step_index: int | None) -> str:
        relative = self.artifacts.relative(captured.path)
        recorder.add_screenshot(
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

    def _capture_failure_screenshots(self, recorder: TestRecorder, runner: ScenarioRunner) -> None:
        """失敗時の各参加プレイヤーの画面を残す。ウィンドウが無い等で撮れなければ諦める。"""
        step_index = recorder.failure.step_index if recorder.failure is not None else None
        for player in runner.players.values():
            if not player.running:
                continue
            # 撮影の間だけウィンドウを待つ時間を短くする（再起動後のウィンドウ探しは元の時間で待つ）
            timeout = player.window_timeout
            player.window_timeout = FAILURE_WINDOW_TIMEOUT
            try:
                player.check_alive()
                captured = runner.capture(player, FAILURE_SCREENSHOT)
            except Exception as error:  # noqa: BLE001 - 診断用の撮影失敗で本来のエラーを隠さない
                logger.warning("%s: could not capture a failure screenshot: %s", player.name, error)
                continue
            finally:
                player.window_timeout = timeout
            self._record_screenshot(recorder, captured, step_index)

    def _record_failure(self, message: str) -> None:
        logger.error("%s failed: %s", self.phase, message)
        self.recorder.fail(self.phase, message)
        # 走らなかったテストは not run のまま（run の failure がその理由を示す）

    def _interrupted(self) -> None:
        """Ctrl+C / SIGTERM。走り切れなかったテストは _run_test が skipped にしている。"""
        logger.error("interrupted during %s", self.phase)
        self.recorder.fail("interrupted", f"interrupted during {self.phase}")


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


def _summary_text(result) -> str:
    summary = result.summary
    return f"{summary.passed} passed, {summary.failed} failed, {summary.error} error, {summary.skipped} skipped"


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def _dead_client(runner: ScenarioRunner) -> ClientDiedError | None:
    """テストの参加プレイヤーのクライアントが死んでいればその例外、全員生きていれば None。"""
    try:
        runner.check_players_alive()
    except ClientDiedError as error:
        return error
    return None
