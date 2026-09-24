"""YAML 1.2 と同じ真偽値の規則で YAML を読む。

PyYAML は YAML 1.1 に従い on / off / yes / no を真偽値として読むため、
シナリオの `on: server` がキー True に化けてしまう。true / false だけを真偽値として扱うローダーを用意する。
"""

import re
from typing import Any

import yaml

BOOL_TAG = "tag:yaml.org,2002:bool"


class Yaml12SafeLoader(yaml.SafeLoader):
    """SafeLoader の真偽値の判定だけを YAML 1.2 相当に変えたローダー。"""


# 親クラスのリストを書き換えないよう、辞書もリストも作り直してから bool の規則を差し替える
Yaml12SafeLoader.yaml_implicit_resolvers = {
    first: [(tag, pattern) for tag, pattern in resolvers if tag != BOOL_TAG]
    for first, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
Yaml12SafeLoader.add_implicit_resolver(BOOL_TAG, re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"), list("tTfF"))


def load_yaml(text: str) -> Any:
    """YAML（JSON も可）の文書を安全に読み込む。"""
    return yaml.load(text, Loader=Yaml12SafeLoader)
