"""fukurou run の入力をまとめたデータ。"""

from dataclasses import dataclass
from pathlib import Path

from fukurou.scenario import ScenarioSource


@dataclass(frozen=True)
class RunOptions:
    """コマンドライン引数を解釈した結果。CLI 以外（テストなど）からも実行できるよう argparse から切り離す。"""

    minecraft_version: str
    # シナリオファイルのパスか、インラインのテキストのどちらか一方
    scenario_file: Path | None
    scenario_text: str | None
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

    def scenario_source(self) -> ScenarioSource:
        if self.scenario_file is not None:
            return ScenarioSource.from_file(self.scenario_file)
        return ScenarioSource.inline(self.scenario_text or "")
