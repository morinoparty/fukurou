"""シナリオの前に行う組み込みの確認: プラグインが読み込まれて有効化されたか。"""

from dataclasses import dataclass
import re
import time
from typing import Callable

from fukurou.errors import FukurouError

# どのプラグインであっても、これらがサーバーログに出たら読み込みか有効化に失敗している
ERROR_MARKERS = (
    "Could not load plugin",
    "Could not load '",
    "Error occurred while enabling",
    "Unsupported class file major version",
    "Failed to remap plugin",
)


class PluginCheckError(FukurouError):
    """プラグインの読み込み・有効化に失敗した場合に送出する。どのプラグインが有効になったかも持つ。"""

    def __init__(self, message: str, enabled: dict[str, bool]):
        super().__init__(message)
        self.enabled = enabled


@dataclass(frozen=True)
class PluginIdentity:
    """サーバーログの行をプラグインに結び付けるための情報。"""

    # plugin.yml / paper-plugin.yml の name
    name: str
    # plugins/ に置いた jar のファイル名（読み込み失敗のログはファイル名で出る）
    file_name: str
    # plugin.yml の prefix。設定されていると、そのプラグインのログは [<prefix>] で出る
    log_prefix: str | None = None

    @property
    def log_name(self) -> str:
        """Bukkit / Paper は名前の空白を _ に置き換えて扱う。"""
        return self.name.replace(" ", "_")

    @property
    def enabling_marker(self) -> str:
        """有効化の開始時に出るログ。版は必須なので、名前の後には必ず " v" が続く。"""
        return f"[{self.log_prefix or self.log_name}] Enabling {self.log_name} v"

    def owns_error(self, line: str) -> bool:
        """エラー行がこのプラグイン自身についてのものか。

        依存先の名前や、名前を部分として含む別プラグイン（Core と MineStampCore など）の行を
        取り違えないよう、ファイル名の完全一致か「enabling <名前> v」でだけ結び付ける。
        """
        if f"enabling {self.log_name} v" in line:
            return True
        file_token = rf"(?<![\w.-]){re.escape(self.file_name)}(?![\w.-])"
        return re.search(file_token, line) is not None


@dataclass(frozen=True)
class PluginLogState:
    """ある時点のサーバーログから読み取った、プラグインの状態。"""

    enabled: dict[str, bool]
    errors: list[str]

    @classmethod
    def read(cls, log: str, plugins: list[PluginIdentity]) -> "PluginLogState":
        errors = [line.strip() for line in log.splitlines() if any(marker in line for marker in ERROR_MARKERS)]
        enabled = {
            # 有効化の開始ログの後に有効化の失敗が出ることもあるため、自身のエラー行があれば無効とみなす
            plugin.name: plugin.enabling_marker in log and not any(plugin.owns_error(line) for line in errors)
            for plugin in plugins
        }
        return cls(enabled=enabled, errors=errors)

    def done(self) -> bool:
        return bool(self.errors) or all(self.enabled.values())


def check_plugins(
    read_log: Callable[[], str],
    plugins: list[PluginIdentity],
    timeout: float,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, bool]:
    """全プラグインの有効化を確認し、プラグイン名 → 有効化できたか を返す。失敗があれば例外を送出する。

    起動完了（Done）の時点で通常は有効化が終わっているため、timeout は短くてよい。
    """
    deadline = time.monotonic() + timeout
    state = PluginLogState.read(read_log(), plugins)
    while not state.done() and time.monotonic() < deadline:
        sleep(0.5)
        state = PluginLogState.read(read_log(), plugins)
    problems = [*state.errors]
    for plugin in plugins:
        # エラー行で原因が分かるプラグインは、重ねて「有効化されなかった」とは書かない
        if not state.enabled[plugin.name] and not any(plugin.owns_error(line) for line in state.errors):
            marker = plugin.enabling_marker
            problems.append(f"{plugin.name} was not enabled (no {marker!r} in the server log)")
    if problems:
        raise PluginCheckError("plugin check failed: " + "; ".join(problems), state.enabled)
    return state.enabled
