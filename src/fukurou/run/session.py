"""1 つのサーバーセッション（サーバー 1 回の起動と、そこに参加するクライアント群）の操作。

SuiteRun がテストの進行を決め、GameSession は「起動する・参加させる・リセットする・再起動する・止める」
といったゲーム側の操作と、ログウィンドウの管理を引き受ける。記録は RunRecorder に流し込むだけで判断はしない。
"""

import logging
import re
import time
from typing import Callable

from fukurou.result.model import LogInfo, LogRange, ResetInfo
from fukurou.result.recorder import RunRecorder
from fukurou.run.artifacts import ArtifactCollector, session_client_log, session_server_log
from fukurou.run.isolation import PLAYER_MISSING, reset_commands
from fukurou.runner.client import ClientDiedError
from fukurou.runner.log_window import LogWindow
from fukurou.runner.player_session import PlayerSession
from fukurou.runner.process import GameProcessError, tail_log
from fukurou.scenario import ResetSpec, TestSpec
from fukurou.server.plugin_checks import PluginCheckError, PluginIdentity, check_plugins
from fukurou.server.process import ServerProcess

logger = logging.getLogger(__name__)

# Paperclip の展開とワールド生成を含む
SERVER_START_TIMEOUT = 600.0
CLIENT_JOIN_TIMEOUT = 900.0
# 起動完了の時点で有効化は終わっているはずなので短くてよい
PLUGIN_CHECK_TIMEOUT = 15.0
JOIN_POLL_SECONDS = 1.0


class GameSession:
    """サーバーの起動 1 回分。restart_fresh() で index を進めて次のセッションになる。"""

    def __init__(
        self,
        server_factory: Callable[[int], ServerProcess],
        players: dict[str, PlayerSession],
        join_order: list[str],
        plugins: list[PluginIdentity],
        artifacts: ArtifactCollector,
        recorder: RunRecorder,
        reset_spec: ResetSpec,
        sleep: Callable[[float], None] = time.sleep,
    ):
        # index を受け取り、サーバーディレクトリを用意した新しい ServerProcess を返す
        self.server_factory = server_factory
        self.players = players
        self.join_order = join_order
        self.plugins = plugins
        self.artifacts = artifacts
        self.recorder = recorder
        self.reset_spec = reset_spec
        self.sleep = sleep
        self.index = -1
        self.server: ServerProcess | None = None
        # このセッションで参加したプレイヤー（参加順）
        self.joined: list[str] = []
        # このセッションでの各プレイヤーの起動回数（ログの回収名の k）
        self.launches: dict[str, int] = {}
        # artifact 内のログのパス → ウィンドウ（サーバーと、参加中のクライアントの現在の latest.log）
        self.windows: dict[str, LogWindow] = {}
        # プレイヤー名 → 現在の起動のクライアントログのパス（logRanges のキー）
        self.client_paths: dict[str, str] = {}
        # 直近のテストで mark() したログのパス
        self._marked: list[str] = []
        # 次に使う前に起動し直すプレイヤー → その理由（画面が開いたままかもしれない / サーバーから切断されている）
        self.dirty: dict[str, str] = {}

    # --- 起動と参加 --------------------------------------------------------------

    def start(self, kind: str = "initial") -> None:
        """サーバーを起動し、プラグインの有効化を確認する。失敗は GameProcessError / PluginCheckError。"""
        self.index += 1
        self.joined = []
        self.launches = {}
        self.windows = {}
        self.client_paths = {}
        self._marked = []
        self.dirty = {}
        self.server = self.server_factory(self.index)
        self.recorder.add_session(kind)
        logger.info("session %d (%s): starting the server on port %d", self.index, kind, self.server.port)
        try:
            self.server.start(timeout=SERVER_START_TIMEOUT)
        except GameProcessError:
            logger.error("server output (last lines):\n%s", tail_log(self.server.log_path))
            raise
        self.windows[session_server_log(self.index)] = LogWindow(self.server.log_path)
        self._check_plugins()

    def join_all(self) -> None:
        """和集合のプレイヤーを 1 人ずつ参加させる。同時に起動すると CPU を取り合い、読み込みが大幅に遅くなるため。"""
        for name in self.join_order:
            self.join(name)

    def join(self, name: str) -> None:
        """クライアントを起動し、サーバーログに参加の行が出るまで待つ。"""
        player = self.players[name]
        # 同じセッションでの再参加でも前回の "joined the game" に一致しないよう、起動前の位置から見る
        probe = LogWindow(self.server.log_path)
        probe.mark()
        logger.info("%s: starting the client", name)
        player.start(self.server.port)
        self.launches[name] = self.launches.get(name, 0) + 1
        joined = re.compile(rf"\b{re.escape(name)} joined the game")
        deadline = time.monotonic() + CLIENT_JOIN_TIMEOUT
        while not joined.search(probe.read()):
            if time.monotonic() > deadline:
                raise GameProcessError(f"{name} did not join within {CLIENT_JOIN_TIMEOUT:.0f} seconds")
            try:
                player.check_alive()
            except ClientDiedError:
                logger.error("%s client output (last lines):\n%s", name, tail_log(player.launch_log))
                raise
            self.server.check_alive()
            self.sleep(JOIN_POLL_SECONDS)
        logger.info("%s joined", name)
        if name not in self.joined:
            self.joined.append(name)
        self.recorder.mark_joined(name)
        session_info = self.recorder.sessions[self.index]
        if name not in session_info.players:
            session_info.players.append(name)
        # この起動の latest.log を、artifact での置き場のパスをキーにして追う
        path = session_client_log(self.index, name, self.launches[name])
        self.client_paths[name] = path
        self.windows[path] = LogWindow(player.latest_log)

    def restart_fresh(self, on_phase: Callable[[str], None] | None = None) -> None:
        """今のセッションを閉じ、まっさらなサーバーで次のセッションを始めて全員を再参加させる。

        on_phase は失敗の段階（server-start / client-join）を呼び出し側が記録するための通知。
        """
        self.stop()
        if on_phase is not None:
            on_phase("server-start")
        self.start(kind="fresh-server")
        if on_phase is not None:
            on_phase("client-join")
        self.join_all()

    # --- テストの前後 -------------------------------------------------------------

    def needs_relaunch(self, name: str) -> str | None:
        """クライアントを起動し直すべき理由（dirty / 死亡）。不要なら None。"""
        if name in self.dirty:
            return self.dirty[name]
        try:
            self.players[name].check_alive()
        except ClientDiedError as error:
            return str(error)
        return None

    def relaunch(self, name: str) -> None:
        """クライアントを止めてログを回収し、起動し直して参加を待つ。失敗は呼び出し側で run の失敗にする。"""
        player = self.players[name]
        logger.info("%s: relaunching the client", name)
        player.stop()
        info = self._collect_client_log(name)
        if info is not None:
            self.recorder.add_session_log(self.index, info)
        self.windows.pop(self.client_paths.pop(name, ""), None)
        self.dirty.pop(name, None)
        self.join(name)

    def reset(self, test: TestSpec) -> ResetInfo:
        """ワールドと参加プレイヤーをテストの前の状態に戻す。

        RCON の応答を検査し、失敗したコマンドがあればそこで止めて error に記録する。
        サーバーが応答しなければ ServerUnavailableError がそのまま上がる。

        終わりに、mark_logs() したログのここまでの行を照合の対象から外す（logRanges には残る）。
        リセットのコマンドはサーバーログ（logAdminCommands）と OP のクライアントのチャットに写るので、
        外さないとテストの wait_for_log / assert_no_log がハーネス自身の出力に一致してしまう。
        """
        started = time.monotonic()
        error = None
        for command in reset_commands(test, self.joined, self.reset_spec):
            response = self.server.command(command.command)
            if command.player is not None and PLAYER_MISSING in response:
                # プロセスは生きていてもサーバーに居ない（キック・接続の切断）。次に参加するテストの前に起動し直す。
                # 参加しないプレイヤーの駐機ではエラーにしない（is_error が ignore する）ので、判定より前に覚える
                self.dirty[command.player] = "the client was not connected to the server"
            if command.is_error(response):
                error = f"{command.command}: {response.strip()}"
                logger.error("reset failed: %s", error)
                break
            logger.debug("reset: %s -> %s", command.command, response.strip())
        if error is None:
            for player in test.players:
                if player.name in self.joined:
                    self.players[player.name].normalize_view()
            self.sleep(self.reset_spec.settle)
        for path in self._marked:
            self.windows[path].skip()
        return ResetInfo(duration_ms=int((time.monotonic() - started) * 1000), error=error)

    def mark_logs(self, names: list[str]) -> None:
        """サーバーログと、指定したプレイヤーのクライアントログにテストの開始位置を付ける。

        reset() の前に呼ぶ。logRanges はここから始まり（リセットの応答も含む）、
        ステップの照合はリセットの後の行だけを見る（reset() の終わりで skip() する）。
        """
        self._marked = [session_server_log(self.index)]
        self._marked += [self.client_paths[name] for name in names if name in self.client_paths]
        for path in self._marked:
            self.windows[path].mark()

    def server_log(self) -> LogWindow:
        return self.windows[session_server_log(self.index)]

    def player_logs(self, names: list[str]) -> dict[str, LogWindow]:
        return {name: self.windows[self.client_paths[name]] for name in names if name in self.client_paths}

    def log_ranges(self) -> dict[str, LogRange]:
        """mark_logs() 以降に書かれた行の範囲。1 行も無いログは載せない。"""
        ranges = {}
        for path in self._marked:
            line_range = self.windows[path].line_range()
            if line_range is not None:
                ranges[path] = line_range
        return ranges

    def mark_dirty(self, names: set[str]) -> None:
        """passed で終わらなかったテストで入力を受けたプレイヤー。画面が開いたままかもしれない。"""
        for name in names:
            self.dirty.setdefault(name, "the previous test left keys pressed or a screen open")

    def server_alive(self) -> bool:
        return self.server is not None and self.server.is_running()

    # --- 停止と回収 ---------------------------------------------------------------

    def stop(self, failure: str | None = None) -> None:
        """クライアントとサーバーを止め、ログを回収してセッションを閉じる。最後まで進めてから最初の失敗を投げる。"""
        if self.server is None:
            return
        errors: list[Exception] = []
        # 参加を確認できなかったクライアントや、起動の途中で失敗した（ディスプレイだけ残った）ものも止める
        for player in self.players.values():
            try:
                player.stop()
            except Exception as error:  # noqa: BLE001 - 後片付けは最後まで進める
                logger.exception("could not stop %s", player.name)
                errors.append(error)
        try:
            self.server.stop()
        except Exception as error:  # noqa: BLE001
            logger.exception("could not stop the server")
            errors.append(error)
        try:
            logs = self.collect_logs()
        except Exception as error:  # noqa: BLE001
            logger.exception("could not collect logs")
            errors.append(error)
            logs = []
        self.recorder.finish_session(self.index, logs, failure)
        self.server = None
        if errors:
            raise errors[0]

    def collect_logs(self) -> list[LogInfo]:
        """サーバーのコンソールの記録と、起動したクライアントの現在の latest.log、クラッシュレポートを回収する。"""
        infos: list[LogInfo] = []
        info = self.artifacts.collect_server_log(self.index, self.server.log_path)
        if info is not None:
            infos.append(info)
        for name in self.launches:
            info = self._collect_client_log(name)
            if info is not None:
                infos.append(info)
            infos.extend(self.artifacts.collect_crash_reports(name, self.players[name].crash_reports_dir))
        return infos

    def _collect_client_log(self, name: str) -> LogInfo | None:
        player = self.players[name]
        return self.artifacts.collect_client_log(self.index, name, self.launches.get(name, 1), player.latest_log)

    def _check_plugins(self) -> None:
        names = [identity.name for identity in self.plugins]
        try:
            enabled = check_plugins(self.server_log().read, self.plugins, PLUGIN_CHECK_TIMEOUT)
        except PluginCheckError as error:
            self.recorder.set_plugin_enabled(error.enabled)
            raise
        self.recorder.set_plugin_enabled(enabled)
        logger.info("plugins enabled: %s", ", ".join(names) or "none")
