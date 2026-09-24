"""シナリオ全体（参加するプレイヤーとステップ列）のモデルと読み込み処理。"""

from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Discriminator, Field, Tag, ValidationError, model_validator

from fukurou.scenario.common import SERVER_TARGET, PlayerName, Wait
from fukurou.scenario.player_actions import AnyPlayerAction, PlayerAction, Screenshot
from fukurou.scenario.server_actions import AnyServerAction


# 失敗時に各プレイヤーの画面を保存するときのスクリーンショット名
FAILURE_SCREENSHOT = "failure"


class ScenarioError(ValueError):
    """シナリオの形式が不正な場合に送出する。"""


class PlayerSpec(BaseModel):
    """シナリオに参加するプレイヤー。起動順に参加し、op が true なら参加後に OP にする。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    name: PlayerName
    op: bool = False


def _step_target(step: Any) -> str:
    """on の値から、ステップをどの種類のアクションとして検証するかを決める。"""
    on = step.get("on") if isinstance(step, dict) else getattr(step, "on", None)
    if on is None:
        return "common"
    return "server" if on == SERVER_TARGET else "player"


# on が無ければ共通アクション、"server" ならサーバー、それ以外はプレイヤーのアクションとして検証する
Step = Annotated[
    Annotated[Wait, Tag("common")] | Annotated[AnyServerAction, Tag("server")] | Annotated[AnyPlayerAction, Tag("player")],
    Discriminator(_step_target),
]


class Scenario(BaseModel):
    """シナリオ全体。players に宣言したプレイヤーだけが steps の on に書ける。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    # エディターの補完用に JSON Schema の URL を書けるようにする（実行には使わない）
    schema_url: str | None = Field(default=None, alias="$schema")
    players: Annotated[list[PlayerSpec], Field(min_length=1)]
    steps: Annotated[list[Step], Field(min_length=1)]

    @model_validator(mode="after")
    def _check_references(self) -> "Scenario":
        names = [player.name for player in self.players]
        if len(set(names)) != len(names):
            raise ValueError("player names must be unique")
        if SERVER_TARGET in names:
            raise ValueError(f"{SERVER_TARGET!r} is reserved and cannot be a player name")
        for index, step in enumerate(self.steps):
            if isinstance(step, PlayerAction) and step.on not in names:
                raise ValueError(f"step {index}: player {step.on!r} is not declared in players")
        # スクリーンショットはプレイヤーごとのディレクトリに保存するため、同じプレイヤー内で重複を弾く
        shots = [(step.on, step.name) for step in self.steps if isinstance(step, Screenshot)]
        duplicates = sorted({f"{on}/{name}" for on, name in shots if shots.count((on, name)) > 1})
        if duplicates:
            raise ValueError(f"duplicate screenshot names: {', '.join(duplicates)}")
        # 失敗時の画面は "failure" という名前で保存するため、シナリオでは使えないようにする
        if any(name == FAILURE_SCREENSHOT for _, name in shots):
            raise ValueError(f"screenshot name {FAILURE_SCREENSHOT!r} is reserved for the failure screenshot")
        return self


def parse_scenario(data: Any) -> Scenario:
    """JSON / YAML から読み込んだ値を検証し、Scenario に変換する。"""
    try:
        return Scenario.model_validate(data)
    except ValidationError as error:
        raise ScenarioError(str(error)) from error
