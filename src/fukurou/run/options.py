"""fukurou run の入力をまとめたデータ。"""

from dataclasses import dataclass
from pathlib import Path

from fukurou.scenario.discovery import Selection


@dataclass(frozen=True)
class RunOptions:
    """コマンドライン引数を解釈した結果。CLI 以外（テストなど）からも実行できるよう argparse から切り離す。"""

    minecraft_version: str
    # 実行するテストの選択（スイート・シナリオファイル・インライン・フィルタ）
    selection: Selection
    # 最初の失敗で残りのテストを skipped にしてスイートを止める
    fail_fast: bool
    accept_eula: bool
    plugins_dir: Path
    plugins: str
    dependencies: str
    server_properties: str
    server_files: Path | None
    server_build: int | None
    java: Path | None
    client_java: Path | None
    work_dir: Path
    out_dir: Path
