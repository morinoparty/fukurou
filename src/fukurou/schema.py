"""シナリオ・スイート・result.json の JSON Schema を生成する。schema/*.json はこの出力をそのまま保存したもの。"""

import json
from typing import Any

from pydantic import BaseModel

from fukurou.scenario import Scenario, Suite

JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
SCHEMA_NAMES = ("scenario", "suite", "result")
# fukurou schema <name> の出力を保存するファイル名（schema/ の下）。形が変わるとファイル名の版を上げる
SCHEMA_FILES = {"scenario": "scenario.v2.json", "suite": "suite.v1.json", "result": "result.v2.json"}
DESCRIPTIONS = {
    "scenario": "A fukurou test: the players that join, the steps to run on the server and the clients, and metadata.",
    "suite": "A fukurou suite file: the scenarios to run and the players, fixtures and reset settings they share.",
    "result": "result.json written by fukurou run (schemaVersion 2).",
}


def build_schema(name: str) -> dict[str, Any]:
    """名前に対応する JSON Schema を dict で返す。"""
    if name not in SCHEMA_NAMES:
        raise ValueError(f"unknown schema: {name}")
    schema = _model(name).model_json_schema(by_alias=True)
    # docstring 由来の説明は開発者向けの日本語なので、公開するスキーマからは取り除く
    schema = _strip_descriptions(schema)
    _require_action_tags(schema)
    return {"$schema": JSON_SCHEMA_DIALECT, "description": DESCRIPTIONS[name], **schema}


def _model(name: str) -> type[BaseModel]:
    if name == "scenario":
        return Scenario
    if name == "suite":
        return Suite
    # result.json のモデルは契約側（fukurou.result.model）が持つ。scenario / suite の出力だけを使う
    # 経路（アクションの早期検証など）で読み込まずに済むよう、必要になったときに import する
    from fukurou.result.model import ResultV2

    return ResultV2


def schema_json(name: str) -> str:
    """保存・表示用に整形した JSON 文字列（末尾に改行付き）。"""
    return json.dumps(build_schema(name), indent=2, ensure_ascii=False) + "\n"


def _strip_descriptions(node: Any) -> Any:
    if isinstance(node, dict):
        # properties の中の "description" という名前のプロパティは残すため、キーが値の型を持つ場合だけ消す
        return {
            key: _strip_descriptions(value)
            for key, value in node.items()
            if not (key == "description" and isinstance(value, str))
        }
    if isinstance(node, list):
        return [_strip_descriptions(item) for item in node]
    return node


def _require_action_tags(schema: dict[str, Any]) -> None:
    """アクションの判別に使う action は既定値を持つため pydantic は必須にしないが、実際には必須なので必須にする。"""
    for definition in schema.get("$defs", {}).values():
        action = definition.get("properties", {}).get("action")
        if isinstance(action, dict) and "const" in action:
            required = definition.setdefault("required", [])
            if "action" not in required:
                required.insert(0, "action")
