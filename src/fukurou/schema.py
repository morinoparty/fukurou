"""シナリオと result.json の JSON Schema を生成する。schema/*.json はこの出力をそのまま保存したもの。"""

import json
from typing import Any

from fukurou.result.model import ResultV1
from fukurou.scenario import Scenario

JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
SCHEMA_NAMES = ("scenario", "result")
DESCRIPTIONS = {
    "scenario": "A fukurou scenario: the players that join and the steps to run on the server and the clients.",
    "result": "result.json written by fukurou run (schemaVersion 1).",
}


def build_schema(name: str) -> dict[str, Any]:
    """名前に対応する JSON Schema を dict で返す。"""
    if name == "scenario":
        schema = Scenario.model_json_schema()
    elif name == "result":
        schema = ResultV1.model_json_schema(by_alias=True)
    else:
        raise ValueError(f"unknown schema: {name}")
    # docstring 由来の説明は開発者向けの日本語なので、公開するスキーマからは取り除く
    schema = _strip_descriptions(schema)
    _require_action_tags(schema)
    return {"$schema": JSON_SCHEMA_DIALECT, "description": DESCRIPTIONS[name], **schema}


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
