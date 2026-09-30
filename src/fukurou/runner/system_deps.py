"""バックグラウンドで入れているシステムパッケージ（Xvfb・xdotool・Mesa など）の完了を待つ。

アクション（action.yml）は apt-get をバックグラウンドで始め、ログと終了コードのファイルを置くディレクトリを
FUKUROU_SYSTEM_DEPS_STATUS で fukurou run に渡す。パッケージはクライアント側（仮想ディスプレイ・入力・描画）に
しか要らないため、サーバーの起動とは並行させ、最初のクライアントを起動する直前にだけ待つ。
環境変数が無い（手元での実行や skip-system-deps）ときは何も待たない。
失敗・時間切れのときだけ apt のログをここで表示する（成功時のログはアクションの後続のステップが表示する）。
"""

import logging
import os
from pathlib import Path
import sys
import threading
import time
from typing import Callable, TextIO

from fukurou.runner.process import GameProcessError

logger = logging.getLogger(__name__)

STATUS_ENV = "FUKUROU_SYSTEM_DEPS_STATUS"
# 状態ディレクトリ内のファイル。exit-code は完了時に一時ファイルからの mv で原子的に作られる
LOG_FILE = "apt.log"
EXIT_CODE_FILE = "exit-code"
# 普段は十数秒で終わる。ミラーが遅い場合も考えて長めに取るが、止まったままにはしない
WAIT_TIMEOUT = 600.0
POLL_SECONDS = 0.5
# 時間切れのときに表示するログの末尾の行数
TAIL_LINES = 40


class SystemDepsError(GameProcessError):
    """バックグラウンドのパッケージのインストールが失敗した、または時間内に終わらなかった。"""


class SystemDepsWaiter:
    """状態ディレクトリを見て、インストールの完了を 1 回だけ待つ。成功した後の呼び出しはすぐに戻る。"""

    def __init__(
        self,
        status_dir: Path | None,
        timeout: float = WAIT_TIMEOUT,
        poll: float = POLL_SECONDS,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
        out: TextIO | None = None,
    ):
        self.status_dir = status_dir
        self.timeout = timeout
        self.poll = poll
        self.sleep = sleep
        self.clock = clock
        # 既定では呼び出し時点の標準出力に書く（pytest の capsys で差し替えられるように）
        self.out = out
        # 状態ディレクトリが無ければ待つものが無い
        self._done = status_dir is None
        # 並行するレーンからの再起動が重なっても、待ちとログの表示は 1 回にする
        self._lock = threading.Lock()

    @classmethod
    def from_env(cls, environ: dict[str, str] | None = None) -> "SystemDepsWaiter":
        """FUKUROU_SYSTEM_DEPS_STATUS から作る。未設定または空なら何も待たない。"""
        value = (os.environ if environ is None else environ).get(STATUS_ENV, "")
        return cls(Path(value) if value else None)

    def wait(self) -> None:
        """インストールが終わるまで待つ。失敗・時間切れは apt のログを表示してから SystemDepsError にする。"""
        with self._lock:
            if self._done:
                return
            exit_file = self.status_dir / EXIT_CODE_FILE
            started = self.clock()
            if not exit_file.is_file():
                logger.info("waiting for the system packages (apt-get) to finish installing")
            while not exit_file.is_file():
                if self.clock() - started > self.timeout:
                    self._print_log("apt-get (still running)", tail=TAIL_LINES)
                    raise SystemDepsError(
                        f"the system packages were not installed within {self.timeout:.0f} seconds; "
                        f"see the apt-get log above ({self.status_dir / LOG_FILE})"
                    )
                self.sleep(self.poll)
            code = _read_exit_code(exit_file)
            if code != 0:
                # 成功時のログはアクションの後続のステップが表示する。失敗はエラーの直前に見えるようにここで出す
                self._print_log("apt-get: install the system packages (failed)")
                raise SystemDepsError(
                    f"installing the system packages (Xvfb, xdotool, Mesa) failed with exit code {code}; "
                    "see the apt-get log above"
                )
            logger.info("system packages installed (waited %.1fs)", self.clock() - started)
            self._done = True

    def _print_log(self, title: str, tail: int | None = None) -> None:
        """apt のログを Actions の折りたたみグループで表示する。"""
        try:
            lines = (self.status_dir / LOG_FILE).read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError as error:
            lines = [f"(could not read the apt-get log: {error})"]
        if tail is not None:
            lines = lines[-tail:]
        out = self.out or sys.stdout
        # ワークフローコマンドは行頭に置く必要があるため、[fukurou] の付くロガーではなく直接書く
        out.write(f"::group::{title}\n")
        for line in lines:
            out.write(f"{line}\n")
        out.write("::endgroup::\n")
        out.flush()


def _read_exit_code(path: Path) -> int:
    """終了コードのファイルを読む。壊れていれば失敗として扱う。"""
    try:
        return int(path.read_text(encoding="utf-8").strip())
    except ValueError:
        return -1


# 1 プロセスで 1 回だけ待てばよいので、環境変数から作った待ち合わせを共有する
_waiter: SystemDepsWaiter | None = None
_waiter_lock = threading.Lock()


def wait_for_system_deps() -> None:
    """最初のクライアントの起動前に呼ぶ。FUKUROU_SYSTEM_DEPS_STATUS が無ければ何もしない。"""
    global _waiter
    with _waiter_lock:
        if _waiter is None:
            _waiter = SystemDepsWaiter.from_env()
    _waiter.wait()
