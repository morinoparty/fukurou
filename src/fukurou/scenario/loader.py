"""シナリオを YAML / JSON のファイルまたはインラインの文字列から読み込む。"""

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
from typing import Any

import yaml

from fukurou.result.model import ScenarioInfo
from fukurou.scenario.model import Scenario, ScenarioError, parse_scenario
from fukurou.yaml_loader import load_yaml

INLINE_SOURCE = "inline"


@dataclass(frozen=True)
class ScenarioSource:
    """検証前のシナリオの生テキストと、その出どころ。

    シナリオが不正でも result.json に名前とハッシュを残せるよう、検証とは分けて保持する。
    """

    name: str
    source: str
    text: str

    @classmethod
    def from_file(cls, path: Path) -> "ScenarioSource":
        """ファイルから読み込む。名前はファイル名の拡張子を除いた部分にする。"""
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            raise ScenarioError(f"could not read the scenario file {path}: {error}") from error
        # 利用者が渡したパス表記のまま記録し、ビューアで見覚えのある形にする
        return cls(name=path.stem, source=f"file:{path.as_posix()}", text=text)

    @classmethod
    def inline(cls, text: str) -> "ScenarioSource":
        """アクションの入力などで直接渡された文字列から作る。"""
        return cls(name=INLINE_SOURCE, source=INLINE_SOURCE, text=text)

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()

    def info(self) -> ScenarioInfo:
        """result.json の scenario 欄を作る。"""
        return ScenarioInfo(name=self.name, source=self.source, sha256=self.sha256)

    def parse(self) -> Scenario:
        """テキストを解釈して検証し、Scenario を返す。"""
        return parse_scenario(parse_document(self.text))


def parse_document(text: str) -> Any:
    """JSON または YAML の文書を Python の値に変換する。

    JSON は YAML としても読めるが、タブでインデントした JSON は YAML では構文エラーになるため、
    先に JSON として読み、失敗したら YAML として読む。YAML の `on:` が真偽値にならないよう
    YAML 1.2 相当のローダーを使う。
    """
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    try:
        return load_yaml(text)
    except yaml.YAMLError as error:
        raise ScenarioError(f"the scenario is neither valid JSON nor valid YAML: {error}") from error


def load_scenario(path: Path) -> Scenario:
    """シナリオファイルを読み込み、検証済みの Scenario を返す。"""
    return ScenarioSource.from_file(path).parse()
