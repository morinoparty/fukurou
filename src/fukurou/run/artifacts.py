"""サーバー・クライアントのログやクラッシュレポートを out-dir の決められた場所へ集める。

契約で決めた配置（logs/server.log, logs/clients/<player>.log, crash-reports/<player>/）に揃え、
サーバーやクライアントの本体・アセット・ワールドは含めない。
"""

from pathlib import Path
import shutil

from fukurou.errors import InvalidInputError
from fukurou.result.model import LogInfo

RESULT_FILE = "result.json"
HARNESS_LOG = "logs/harness.log"
SERVER_LOG = "logs/server.log"
SCREENSHOTS_DIR = "screenshots"
# 前回の実行の出力が混ざらないよう、実行前に消す out-dir 内の項目
OUTPUT_ENTRIES = (RESULT_FILE, SCREENSHOTS_DIR, "logs", "crash-reports")
# fukurou が作った out-dir であることを示す印。無い out-dir の既存のディレクトリは利用者のものとみなして消さない
OUT_DIR_MARKER = ".fukurou-out"


class ArtifactCollector:
    """out-dir への出力をまとめて扱う。パスは artifact のルートからの相対パスで記録する。"""

    def __init__(self, out_dir: Path):
        self.out_dir = out_dir

    def reset(self) -> None:
        """out-dir 自体は残し、fukurou が書く項目だけを消す（利用者が別の用途に使っていても壊さない）。

        印の無い out-dir に logs/ などのディレクトリが既にある場合は、利用者のものかもしれないため
        消さずに InvalidInputError を送出する（--out-dir . を指定した場合など）。
        """
        self.out_dir.mkdir(parents=True, exist_ok=True)
        marker = self.out_dir / OUT_DIR_MARKER
        if not marker.exists():
            foreign = [entry for entry in OUTPUT_ENTRIES if (self.out_dir / entry).is_dir()]
            if foreign:
                raise InvalidInputError(
                    f"refusing to delete {', '.join(foreign)} in {self.out_dir}: the directory was not created by "
                    "fukurou; choose an empty or new --out-dir"
                )
            marker.touch()
        for entry in OUTPUT_ENTRIES:
            path = self.out_dir / entry
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink(missing_ok=True)

    def path(self, relative: str) -> Path:
        return self.out_dir / relative

    def relative(self, path: Path) -> str:
        return path.relative_to(self.out_dir).as_posix()

    def harness_log(self) -> LogInfo:
        return LogInfo(kind="harness", path=HARNESS_LOG)

    def collect_server_log(self, candidates: list[Path]) -> LogInfo | None:
        """最初に見つかったサーバーのログ（latest.log、無ければ標準出力の記録）をコピーする。"""
        return self._copy_first(candidates, SERVER_LOG, LogInfo(kind="server", path=SERVER_LOG))

    def collect_client_log(self, player: str, candidates: list[Path]) -> LogInfo | None:
        """クライアントの latest.log（まだ無ければ PortableMC の出力）をコピーする。"""
        relative = f"logs/clients/{player}.log"
        return self._copy_first(candidates, relative, LogInfo(kind="client", player=player, path=relative))

    def collect_crash_reports(self, player: str, crash_dir: Path) -> list[LogInfo]:
        """クライアントがクラッシュしたときのレポートをプレイヤーごとのディレクトリにコピーする。"""
        if not crash_dir.is_dir():
            return []
        infos = []
        for report in sorted(crash_dir.glob("*.txt")):
            relative = f"crash-reports/{player}/{report.name}"
            self._copy(report, relative)
            infos.append(LogInfo(kind="crash", player=player, path=relative))
        return infos

    def _copy_first(self, candidates: list[Path], relative: str, info: LogInfo) -> LogInfo | None:
        for candidate in candidates:
            if candidate.is_file():
                self._copy(candidate, relative)
                return info
        return None

    def _copy(self, source: Path, relative: str) -> None:
        destination = self.out_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
