"""サーバー・クライアントのログやクラッシュレポートを out-dir の決められた場所へ集める。

契約で決めた配置（tests/<id>/screenshots/, logs/sessions/<n>/server.log, logs/sessions/<n>/clients/<player>.log,
crash-reports/<player>/）に揃え、サーバーやクライアントの本体・アセット・ワールドは含めない。
"""

from pathlib import Path
import shutil

from fukurou.errors import InvalidInputError
from fukurou.result.model import LogInfo

RESULT_FILE = "result.json"
HARNESS_LOG = "logs/harness.log"
TESTS_DIR = "tests"
# 前回の実行の出力が混ざらないよう、実行前に消す out-dir 内の項目
OUTPUT_ENTRIES = (RESULT_FILE, TESTS_DIR, "logs", "crash-reports")
# fukurou が作った out-dir であることを示す印。無い out-dir の既存のディレクトリは利用者のものとみなして消さない
OUT_DIR_MARKER = ".fukurou-out"


def session_server_log(index: int) -> str:
    """セッション n のサーバーコンソールの記録の置き場。"""
    return f"logs/sessions/{index}/server.log"


def session_client_log(index: int, player: str, launch: int) -> str:
    """セッション n でのクライアントの latest.log の置き場。再起動 k 回目（k >= 2）は <player>.<k>.log。"""
    suffix = "" if launch <= 1 else f".{launch}"
    return f"logs/sessions/{index}/clients/{player}{suffix}.log"


class ArtifactCollector:
    """out-dir への出力をまとめて扱う。パスは artifact のルートからの相対パスで記録する。"""

    def __init__(self, out_dir: Path):
        self.out_dir = out_dir
        # 既に回収したクラッシュレポート。fresh-server の切り替え時に前のセッションの分を重ねて載せない
        self._collected_reports: set[Path] = set()

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

    def screenshots_dir(self, test_id: str) -> Path:
        """テストのスクリーンショットの置き場（tests/<id>/screenshots/<player>/<name>.png の親）。"""
        return self.out_dir / TESTS_DIR / test_id / "screenshots"

    def harness_log(self) -> LogInfo:
        return LogInfo(kind="harness", path=HARNESS_LOG)

    def collect_server_log(self, index: int, source: Path) -> LogInfo | None:
        """セッションのサーバーコンソールの記録（ログウィンドウの測定対象と同じファイル）をコピーする。"""
        relative = session_server_log(index)
        return self._copy_if_exists(source, relative, LogInfo(kind="server", path=relative))

    def collect_client_log(self, index: int, player: str, launch: int, source: Path) -> LogInfo | None:
        """クライアントの latest.log を、セッションと起動回数に応じた名前でコピーする。"""
        relative = session_client_log(index, player, launch)
        return self._copy_if_exists(source, relative, LogInfo(kind="client", player=player, path=relative))

    def collect_crash_reports(self, player: str, crash_dir: Path) -> list[LogInfo]:
        """クライアントがクラッシュしたときのレポートをプレイヤーごとのディレクトリにコピーする（新しいものだけ）。"""
        if not crash_dir.is_dir():
            return []
        infos = []
        for report in sorted(crash_dir.glob("*.txt")):
            if report in self._collected_reports:
                continue
            self._collected_reports.add(report)
            relative = f"crash-reports/{player}/{report.name}"
            self._copy(report, relative)
            infos.append(LogInfo(kind="crash", player=player, path=relative))
        return infos

    def _copy_if_exists(self, source: Path, relative: str, info: LogInfo) -> LogInfo | None:
        if not source.is_file():
            return None
        self._copy(source, relative)
        return info

    def _copy(self, source: Path, relative: str) -> None:
        destination = self.out_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
