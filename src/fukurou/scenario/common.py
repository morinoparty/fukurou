"""サーバー・プレイヤーのアクションで共有する型と、対象を持たない共通アクション。"""

import re
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, PositiveFloat, field_validator

NonEmptyStr = Annotated[str, Field(min_length=1)]
# on にこの値を書くとサーバー向けのアクションになるため、プレイヤー名としては使えない
SERVER_TARGET = "server"
# Minecraft のユーザー名の規則（3〜16文字の英数字とアンダースコア）。
# JSON Schema でもサーバー向けアクションと区別できるよう、予約語の server を除外しておく
PlayerName = Annotated[
    str,
    Field(pattern=r"^[A-Za-z0-9_]{3,16}$", json_schema_extra={"not": {"const": SERVER_TARGET}}),
]


class ActionModel(BaseModel):
    """全アクション共通の設定。未知のフィールド（タイプミス）はエラーにし、生成後は変更不可にする。"""

    model_config = ConfigDict(extra="forbid", frozen=True)


class LogPatternFields(BaseModel):
    """ログを正規表現で照合するアクションの共通フィールド。"""

    pattern: NonEmptyStr

    @field_validator("pattern")
    @classmethod
    def _compile(cls, pattern: str) -> str:
        # 実行時ではなく読み込み時に正規表現の誤りを検出する
        try:
            re.compile(pattern)
        except re.error as error:
            raise ValueError(f"invalid regular expression: {error}") from error
        return pattern


class Wait(ActionModel):
    """指定秒数だけ待つ。対象（on）を持たない共通アクション。"""

    action: Literal["wait"] = "wait"
    seconds: PositiveFloat
