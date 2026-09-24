"""PortableMC で起動するバニラクライアント。"""

import os
from pathlib import Path
import subprocess

from fukurou.runner.portablemc import ensure_portablemc
from fukurou.runner.process import GameProcessError, start_process, stop_process

# 初回起動の案内画面やポーズを抑止し、GUI の大きさを固定するクライアント設定。
# ソフトウェアレンダリングの複数クライアントとサーバーが CPU を取り合うため、FPS も制限する
CLIENT_OPTIONS = """\
maxFps:30
enableVsync:false
guiScale:2
fullscreen:false
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
pauseOnLostFocus:false
lang:en_us
renderDistance:4
soundCategory_master:0.0
"""
RESOLUTION = "1280x720"


class ClientProcess:
    """PortableMC で起動するバニラクライアント。Quick Play でテスト用サーバーへ直接参加する。"""

    def __init__(
        self,
        tools_dir: Path,
        cache_dir: Path,
        client_dir: Path,
        log_dir: Path,
        minecraft_version: str,
        username: str,
        java: Path | None,
    ):
        self.tools_dir = tools_dir
        self.cache_dir = cache_dir
        self.client_dir = client_dir
        self.minecraft_version = minecraft_version
        self.username = username
        self.java = java
        # インストール時と起動時の PortableMC の出力は別ファイルに残す
        self.install_log = log_dir / f"{username}-install.log"
        self.launch_log = log_dir / f"{username}-launch.log"
        self.process = None

    @property
    def latest_log(self) -> Path:
        """クライアント自身のログ。チャットも [CHAT] 付きでここに出力される。"""
        return self.client_dir / "logs" / "latest.log"

    @property
    def screenshots_dir(self) -> Path:
        """F2 で撮ったスクリーンショットの保存先。"""
        return self.client_dir / "screenshots"

    @property
    def crash_reports_dir(self) -> Path:
        return self.client_dir / "crash-reports"

    def install(self, timeout: float) -> None:
        """クライアント本体とアセットを --dry でダウンロードだけしておく。"""
        self.client_dir.mkdir(parents=True, exist_ok=True)
        (self.client_dir / "options.txt").write_text(CLIENT_OPTIONS, encoding="utf-8")
        installer = start_process(self._command() + ["--dry"], self.install_log, self.tools_dir)
        try:
            code = installer.wait(timeout=timeout)
        except subprocess.TimeoutExpired as error:
            stop_process(installer, grace_seconds=5)
            raise GameProcessError(f"{self.username}: client installation timed out after {timeout:.0f}s") from error
        except BaseException:
            # 中断（Ctrl+C / SIGTERM）でも止める。別セッションで起動しているためシグナルは届かず、後片付けからも見えない
            stop_process(installer, grace_seconds=5)
            raise
        if code != 0:
            raise GameProcessError(f"{self.username}: client installation failed with code {code}")

    def start(self, server_port: int, display: str) -> None:
        """指定ディスプレイ上でクライアントを起動し、指定ポートのサーバーへ Quick Play で参加させる。"""
        command = self._command() + ["--join-server", "127.0.0.1", "--join-server-port", str(server_port)]
        env = {**os.environ, "DISPLAY": display}
        # GPU の無い CI では Mesa のソフトウェアレンダリングを使う（利用者が明示した値は尊重する）
        env.setdefault("LIBGL_ALWAYS_SOFTWARE", "true")
        self.process = start_process(command, self.launch_log, self.tools_dir, env=env)

    def check_alive(self) -> None:
        """クライアントが落ちていたら例外を送出する。"""
        if self.process is not None and self.process.poll() is not None:
            raise GameProcessError(f"{self.username}: client exited with code {self.process.returncode}")

    def stop(self) -> None:
        if self.process is not None:
            stop_process(self.process, grace_seconds=10)

    def _command(self) -> list:
        return [
            str(ensure_portablemc(self.tools_dir)),
            "--output",
            "machine",
            "--main-dir",
            str(self.cache_dir),
            "start",
            self.minecraft_version,
            "--mc-dir",
            str(self.client_dir),
            # 複数クライアントを同じランナーで動かすため、ヒープは控えめにする
            "--jvm-arg=-Xms512M,-Xmx1536M",
            "--resolution",
            RESOLUTION,
            "--username",
            self.username,
        ] + (["--jvm", str(self.java)] if self.java else [])
