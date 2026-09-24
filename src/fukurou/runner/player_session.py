"""1人のプレイヤー（仮想ディスプレイ・クライアント・ウィンドウ）をまとめて扱う。"""

import logging
from pathlib import Path
import shutil
import struct
import time

from fukurou.errors import FukurouError
from fukurou.runner.client import ClientProcess
from fukurou.runner.process import read_log
from fukurou.runner.x11_input import MinecraftWindow
from fukurou.runner.xvfb import VirtualDisplay

logger = logging.getLogger(__name__)

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# 視点の切り替えキー。3 回で一人称に戻る
PERSPECTIVE_KEY = "F5"
PERSPECTIVES = 3


class PlayerSessionError(FukurouError):
    """スクリーンショットの取得など、プレイヤー操作の結果が期待どおりでない場合に送出する。"""


class PlayerSession:
    """プレイヤー1人分のクライアントと、その専用ディスプレイ。

    セッションの中で stop() → start() と起動し直せる（dirty / 死亡したクライアントの再起動と fresh-server）。
    """

    def __init__(self, name: str, client: ClientProcess, display: VirtualDisplay, window_timeout: float):
        self.name = name
        self.client = client
        self.display = display
        self.window_timeout = window_timeout
        # ウィンドウはキー入力が必要になった時点で探す
        self._window = None
        # start() の回数。ログの回収名（<player>.log / <player>.<k>.log）に使う
        self.launches = 0
        self.running = False
        # ハーネスが送った F5 の回数（mod 3 で視点が分かる）。リセット時に一人称へ戻すのに使う
        self.perspective = 0

    @property
    def latest_log(self) -> Path:
        """クライアント自身のログ。チャットも [CHAT] 付きでここに出力される。"""
        return self.client.latest_log

    @property
    def launch_log(self) -> Path:
        return self.client.launch_log

    @property
    def crash_reports_dir(self) -> Path:
        return self.client.crash_reports_dir

    def install(self, timeout: float) -> None:
        """クライアント本体とアセットをダウンロードしておく（起動はしない）。"""
        self.client.install(timeout=timeout)

    def start(self, server_port: int) -> None:
        """専用ディスプレイを起動し、その上でクライアントを起動してサーバーへ参加させる。"""
        display_name = self.display.start()
        logger.info("%s: display %s", self.name, display_name)
        self.client.start(server_port, display_name)
        self.launches += 1
        self.running = True
        self.perspective = 0

    def press_key(self, keysym: str) -> None:
        self.window().press_key(keysym)
        if keysym == PERSPECTIVE_KEY:
            self.perspective += 1

    def type_text(self, text: str) -> None:
        self.window().type_text(text)

    def chat(self, text: str) -> None:
        """T でチャット欄を開き、入力して送信する。"""
        self.press_key("t")
        # チャット欄が開く前に入力すると先頭の文字が欠けるため、少し待つ
        time.sleep(0.5)
        self.type_text(text)
        self.press_key("Return")

    def normalize_view(self) -> None:
        """テストの前にチャット HUD を消し（F3+d）、視点を一人称へ戻す（F5）。ベストエフォート。

        画面を開いたまま押した F5 は数え損なうため、確実ではない。失敗しても警告に留める。
        """
        try:
            self.window().press_chord("F3", "d")
            for _ in range((PERSPECTIVES - self.perspective % PERSPECTIVES) % PERSPECTIVES):
                self.window().press_key(PERSPECTIVE_KEY)
        except FukurouError as error:
            logger.warning("%s: could not normalize the view: %s", self.name, error)
        self.perspective = 0

    def log(self) -> str:
        """クライアントのログ全体を返す。"""
        return read_log(self.latest_log)

    def take_screenshot(self, destination: Path) -> tuple[int, int]:
        """F2 でスクリーンショットを撮って destination へコピーし、画像の幅と高さを返す。"""
        before = set(self.client.screenshots_dir.glob("*.png"))
        self.press_key("F2")
        screenshot = self._wait_for_new_png(before, timeout=15.0)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(screenshot, destination)
        width, height = png_size(destination)
        logger.info("%s: saved %s (%dx%d)", self.name, destination, width, height)
        return width, height

    def window(self) -> MinecraftWindow:
        if self._window is None:
            self._window = MinecraftWindow.wait_for(self.display.name, self.window_timeout)
        return self._window

    def check_alive(self) -> None:
        self.client.check_alive()

    def stop(self) -> None:
        """クライアントとディスプレイを止める。次の start() で新しいウィンドウを探し直す。"""
        self.client.stop()
        self.display.stop()
        self._window = None
        self.running = False

    def _wait_for_new_png(self, before: set, timeout: float) -> Path:
        """新しい PNG が現れ、書き込みが終わる（サイズが変わらなくなる）まで待つ。"""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            created = sorted(set(self.client.screenshots_dir.glob("*.png")) - before)
            if created:
                candidate = created[-1]
                size = candidate.stat().st_size
                time.sleep(0.5)
                if size > 0 and candidate.stat().st_size == size and _is_png(candidate):
                    return candidate
            time.sleep(0.25)
        raise PlayerSessionError(f"{self.name}: no new screenshot was written after pressing F2")


def _is_png(path: Path) -> bool:
    with path.open("rb") as file:
        return file.read(8) == PNG_SIGNATURE


def png_size(path: Path) -> tuple[int, int]:
    """PNG の IHDR チャンクから幅と高さを読む。"""
    with path.open("rb") as file:
        header = file.read(24)
    if header[:8] != PNG_SIGNATURE:
        raise PlayerSessionError(f"{path} is not a PNG file")
    return struct.unpack(">II", header[16:24])
