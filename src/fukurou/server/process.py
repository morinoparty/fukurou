"""テスト用の Paper サーバーを外部プロセスとして起動・停止する。"""

import os
from pathlib import Path
import subprocess
import time

from fukurou.runner.process import GameProcessError, free_port, read_log, start_process, stop_process
from fukurou.server.rcon import RconClient, RconError

# 起動完了のログ。この後 RCON が待ち受けを開始する
READY_MARKER = "Done ("
HEAP = "-Xmx2G"


class ServerProcess:
    """java -jar で直接起動する Paper サーバー。コマンドは RCON で送る。"""

    def __init__(self, java: Path, jar: Path, server_dir: Path, log_path: Path, bundler_dir: Path):
        self.java = java
        self.jar = jar
        self.server_dir = server_dir
        # 標準出力（コンソール）の記録。起動直後の JVM のエラーも含むため、ログの照合はこちらで行う
        self.log_path = log_path
        # Paperclip が展開するライブラリや Mojang の jar の置き場。実行ごとに作り直さないようキャッシュに置く
        self.bundler_dir = bundler_dir
        self.port = free_port()
        self.rcon_port = free_port()
        # RCON はループバックでのみ待ち受けるが、念のため実行ごとに使い捨てのパスワードを使う
        self.rcon_password = os.urandom(16).hex()
        self.process = None

    def managed_properties(self, max_players: int) -> dict[str, str]:
        """fukurou が制御に使うため、利用者に上書きさせない server.properties の値。"""
        return {
            "server-ip": "127.0.0.1",
            "server-port": str(self.port),
            "enable-rcon": "true",
            "rcon.port": str(self.rcon_port),
            "rcon.password": self.rcon_password,
            "max-players": str(max_players),
        }

    @property
    def latest_log(self) -> Path:
        """サーバー自身が書く logs/latest.log。成果物にはこちらを優先して含める。"""
        return self.server_dir / "logs" / "latest.log"

    def start(self, timeout: float) -> None:
        """サーバーを起動し、起動完了のログが出て RCON で接続できるようになるまで待つ。"""
        command = [
            str(self.java),
            HEAP,
            f"-DbundlerRepoDir={self.bundler_dir}",
            # 古いビルドを使うと更新を促すために起動を20秒止めるため、それを抑止する
            "-DIReallyKnowWhatIAmDoingISwear=true",
            "-jar",
            str(self.jar),
            "--nogui",
        ]
        self.process = start_process(command, self.log_path, self.server_dir)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise GameProcessError(f"server exited with code {self.process.returncode} before it was ready")
            if READY_MARKER in read_log(self.log_path):
                try:
                    self.command("list")
                    return
                except (OSError, RconError):
                    pass
            time.sleep(1.0)
        raise GameProcessError(f"server did not start within {timeout:.0f} seconds")

    def command(self, command: str) -> str:
        """RCON でコンソールコマンドを実行し、応答を返す。"""
        with RconClient("127.0.0.1", self.rcon_port, self.rcon_password) as rcon:
            return rcon.command(command)

    def is_running(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def stop(self) -> None:
        """stop コマンドで正常終了させ、終わらなければプロセスごと止める。"""
        if not self.is_running():
            return
        try:
            self.command("stop")
            self.process.wait(timeout=60)
        except (OSError, RconError, subprocess.TimeoutExpired):
            pass
        stop_process(self.process, grace_seconds=20)
