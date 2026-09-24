"""検証済みのシナリオのステップを、サーバーと各プレイヤーに振り分けて実行する。

ログの照合はファイル全体ではなく、テストの開始時に mark() した LogWindow に対して行う。
同じセッションで前のテストが出した行に一致して偽の成功になるのを防ぐため。
"""

from dataclasses import dataclass
import logging
from pathlib import Path
import re
import time
from typing import Callable

from fukurou.errors import FukurouError
from fukurou.run.isolation import ERROR_RESPONSE
from fukurou.runner.log_window import LogWindow
from fukurou.runner.player_session import PlayerSession
from fukurou.scenario import (
    Chat,
    PlayerAction,
    PlayerAssertNoLog,
    PlayerWaitForLog,
    PressKey,
    Screenshot,
    ServerAction,
    ServerAssertNoLog,
    ServerCommand,
    ServerWaitForLog,
    TypeText,
    Wait,
)
from fukurou.server.process import ServerProcess

logger = logging.getLogger(__name__)


class ScenarioFailure(FukurouError):
    """シナリオの検証（ログ待ち・ログの確認）に失敗した場合に送出する。"""


@dataclass(frozen=True)
class CapturedScreenshot:
    """screenshot ステップで保存した画像。"""

    player: str
    name: str
    path: Path
    width: int
    height: int


class ScenarioRunner:
    """ステップの on を見て、サーバー・プレイヤー・共通のいずれかの処理へ振り分ける。テスト 1 件ごとに作る。"""

    def __init__(
        self,
        server: ServerProcess,
        server_log: LogWindow,
        players: dict[str, PlayerSession],
        player_logs: dict[str, LogWindow],
        screenshots_dir: Path,
    ):
        self.server = server
        self.server_log = server_log
        # プレイヤー名 → PlayerSession（このテストの参加者だけ）
        self.players = players
        # プレイヤー名 → そのクライアントの latest.log のウィンドウ
        self.player_logs = player_logs
        self.screenshots_dir = screenshots_dir
        # press_key / type_text を送ったプレイヤー。テストが passed で終わらなければ画面が開いたままの可能性がある
        self.touched: set[str] = set()

    def run_step(self, step) -> CapturedScreenshot | None:
        """1ステップを実行する。スクリーンショットを撮った場合はその情報を返す。"""
        self.check_players_alive()
        if isinstance(step, ServerAction):
            self._run_server_action(step)
        elif isinstance(step, PlayerAction):
            return self._run_player_action(self.players[step.on], step)
        elif isinstance(step, Wait):
            time.sleep(step.seconds)
        else:
            raise TypeError(f"unsupported step: {step!r}")
        return None

    def screenshot_path(self, player: str, name: str) -> Path:
        """スクリーンショットはプレイヤーごとのディレクトリに保存する。"""
        return self.screenshots_dir / player / f"{name}.png"

    def capture(self, session: PlayerSession, name: str) -> CapturedScreenshot:
        """プレイヤーの画面を撮影して保存する。"""
        path = self.screenshot_path(session.name, name)
        width, height = session.take_screenshot(path)
        return CapturedScreenshot(player=session.name, name=name, path=path, width=width, height=height)

    def check_players_alive(self) -> None:
        for session in self.players.values():
            session.check_alive()

    def _run_server_action(self, action: ServerAction) -> None:
        if isinstance(action, ServerCommand):
            response = self.server.command(action.command)
            logger.info("server: %s", response.strip())
            # ServerProcess.command はエラーの応答でも例外を投げない。fixture の fill / tp が黙って失敗すると
            # 以後のステップが違うワールドで走り、スクリーンショットだけのテストが「通って」しまう
            if ERROR_RESPONSE.search(response):
                raise ScenarioFailure(f"command failed: {response.strip()}")
        elif isinstance(action, ServerWaitForLog):
            self._wait_for_log("server", self.server_log.read, action.pattern, action.timeout)
        elif isinstance(action, ServerAssertNoLog):
            _assert_no_log("server", self.server_log.read(), action.pattern)
        else:
            raise TypeError(f"unsupported server action: {action!r}")

    def _run_player_action(self, session: PlayerSession, action: PlayerAction) -> CapturedScreenshot | None:
        if isinstance(action, PressKey):
            self.touched.add(session.name)
            session.press_key(action.key)
        elif isinstance(action, TypeText):
            self.touched.add(session.name)
            session.type_text(action.text)
        elif isinstance(action, Chat):
            # チャットは開いて送って閉じるまでが 1 つなので、最後まで送れれば画面を残さない。
            # 途中（T で開いた後の入力や Return）で失敗するとチャット欄が開いたままなので、その間だけ touched にする
            already_touched = session.name in self.touched
            self.touched.add(session.name)
            session.chat(action.text)
            if not already_touched:
                self.touched.discard(session.name)
        elif isinstance(action, PlayerWaitForLog):
            self._wait_for_log(session.name, self.player_logs[session.name].read, action.pattern, action.timeout)
        elif isinstance(action, PlayerAssertNoLog):
            _assert_no_log(session.name, self.player_logs[session.name].read(), action.pattern)
        elif isinstance(action, Screenshot):
            return self.capture(session, action.name)
        else:
            raise TypeError(f"unsupported player action: {action!r}")
        return None

    def _wait_for_log(self, source: str, read: Callable[[], str], pattern: str, timeout: float) -> None:
        """ウィンドウ内のログを定期的に読み足し、パターンが現れるまで待つ。"""
        compiled = re.compile(pattern, re.MULTILINE)
        deadline = time.monotonic() + timeout
        while True:
            if compiled.search(read()):
                return
            if time.monotonic() >= deadline:
                break
            self.check_players_alive()
            self.server.check_alive()
            time.sleep(0.5)
        raise ScenarioFailure(f"{source} log did not match {pattern!r} within {timeout:g}s")


def _assert_no_log(source: str, log: str, pattern: str) -> None:
    match = re.search(pattern, log, re.MULTILINE)
    if match:
        raise ScenarioFailure(f"unexpected {source} log line: {match.group(0)!r}")
