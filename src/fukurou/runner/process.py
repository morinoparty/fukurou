"""サーバー・クライアント・Xvfb などの外部プロセスを起動・停止するための共通処理。"""

import os
from pathlib import Path
import re
import signal
import socket
import subprocess

from fukurou.errors import FukurouError

# ANSI のカラーコードはログ照合の邪魔になるので取り除く
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


class GameProcessError(FukurouError):
    """サーバー・クライアントの起動や停止に失敗した場合に送出する。"""


def read_log(path: Path) -> str:
    """ログファイル全体を読み、ANSI エスケープを除いた文字列を返す。未作成なら空文字列。"""
    try:
        return ANSI_ESCAPE.sub("", path.read_text(encoding="utf-8", errors="replace"))
    except FileNotFoundError:
        return ""


def tail_log(path: Path, lines: int = 30) -> str:
    """ログの末尾を返す。失敗の原因を harness.log に残すために使う。"""
    return "\n".join(read_log(path).splitlines()[-lines:])


def free_port() -> int:
    """ループバック上で空いている TCP ポートを1つ返す。"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def start_process(command: list, log_path: Path, cwd: Path, env: dict | None = None) -> subprocess.Popen:
    """コマンドを新しいプロセスグループで起動し、標準出力と標準エラーをログファイルへ書き出す。"""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    cwd.mkdir(parents=True, exist_ok=True)
    with log_path.open("wb") as log:
        # 子孫プロセス（PortableMC → Java など）もまとめて止められるよう start_new_session を指定する
        return subprocess.Popen(
            command,
            cwd=cwd,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            env=env,
        )


def stop_process(process: subprocess.Popen, grace_seconds: float) -> None:
    """プロセスグループに SIGTERM を送り、猶予時間内に終わらなければ SIGKILL する。"""
    if process.poll() is not None:
        return
    for sig, wait in ((signal.SIGTERM, grace_seconds), (signal.SIGKILL, 10.0)):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=wait)
            return
        except subprocess.TimeoutExpired:
            continue
