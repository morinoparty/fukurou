"""シナリオ（= 1 テスト。参加するプレイヤー・ステップ列・メタデータ）のモデルと読み込み処理。"""

from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Discriminator,
    Field,
    PositiveFloat,
    Tag,
    ValidationError,
    field_validator,
    model_validator,
)

from fukurou.scenario.blocks import Parallel, Repeat
from fukurou.scenario.common import SERVER_TARGET, NonEmptyStr, PlayerName, Wait
from fukurou.scenario.expansion import ExpandedStep, StepExpander
from fukurou.scenario.player_actions import AnyPlayerAction, PlayerAction, Screenshot
from fukurou.scenario.server_actions import AnyServerAction
from fukurou.versions import VersionError, parse_spec

# 失敗時に各プレイヤーの画面を保存するときのスクリーンショット名
FAILURE_SCREENSHOT = "failure"
# テストの既定のソフトな期限（秒）。ステップの合間に確認する
DEFAULT_TIMEOUT = 600.0
# reset: 共有セッションでハーネスのリセット後に実行 / fresh-server: サーバーとクライアントを起動し直して実行
Isolation = Literal["reset", "fresh-server"]
# テスト id・fixture 名など、artifact のパスやビューアの URL にそのまま使う名前の規則
SafeName = Annotated[str, Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")]


class ScenarioError(ValueError):
    """シナリオの形式が不正な場合に送出する。"""


class PlayerSpec(BaseModel):
    """テストに参加するプレイヤー。op が true ならそのテストの前のリセットで OP にする（false なら deop）。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    name: PlayerName
    op: bool = False


def _step_target(step: Any) -> str:
    """on の値から、ステップをどの種類のアクションとして検証するかを決める。"""
    on = step.get("on") if isinstance(step, dict) else getattr(step, "on", None)
    if on is None:
        return "common"
    # on がプレイヤー名の一覧（複数プレイヤーの略記）の場合もプレイヤーのアクション
    return "server" if on == SERVER_TARGET else "player"


# on を持たないステップ。wait と、ステップを束ねるブロック（parallel / repeat）を action で見分ける
AnyCommonStep = Annotated[Wait | Parallel | Repeat, Field(discriminator="action")]

# on が無ければ共通アクション・ブロック、"server" ならサーバー、それ以外はプレイヤーのアクションとして検証する
Step = Annotated[
    Annotated[AnyCommonStep, Tag("common")]
    | Annotated[AnyServerAction, Tag("server")]
    | Annotated[AnyPlayerAction, Tag("player")],
    Discriminator(_step_target),
]
# ブロックの steps は Step を再帰的に参照するので、Step を定義した後に解決する
Parallel.model_rebuild(_types_namespace={"Step": Step})
Repeat.model_rebuild(_types_namespace={"Step": Step})


class Scenario(BaseModel):
    """1 つのテスト。v1 の players + steps に、スイートでの実行を制御するメタデータを加えたもの。

    players を省略した場合はスイートの players を使う。ステップの on がプレイヤーを指しているかの
    検証は、players が決まるスイートの展開時（discovery）に行う。
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    # エディターの補完用に JSON Schema の URL を書けるようにする（実行には使わない）
    schema_url: str | None = Field(default=None, alias="$schema")
    # 表示名。省略時はテスト id（ファイル名の stem）を使う
    name: NonEmptyStr | None = None
    # --tag で絞り込むための文字列
    tags: list[NonEmptyStr] = Field(default_factory=list)
    # 省略時はスイートの isolation（既定 reset）
    isolation: Isolation | None = None
    timeout: PositiveFloat = DEFAULT_TIMEOUT
    # このテストを実行する Minecraft バージョン（"1.21.9-" など）。含まれないバージョンでは skipped
    versions: NonEmptyStr | None = None
    # ステップの前に展開するスイートの fixtures の名前（この順に展開する）
    use: list[SafeName] = Field(default_factory=list)
    players: Annotated[list[PlayerSpec], Field(min_length=1)] | None = None
    steps: Annotated[list[Step], Field(min_length=1)]

    @field_validator("versions")
    @classmethod
    def _check_versions(cls, versions: str | None) -> str | None:
        # latest は Paper への通信が要るので、読み込み時点で構文ごと弾く
        if versions is not None:
            try:
                parse_spec(versions)
            except VersionError as error:
                raise ValueError(str(error)) from error
        return versions

    @model_validator(mode="after")
    def _check_references(self) -> "Scenario":
        # ブロックの規則（入れ子・上限・プレースホルダー）は展開して確かめる。fixture を含めた上限は discovery で見る
        expanded = expand_steps(self.steps)
        if self.players is not None:
            check_players(self.players)
            check_step_targets(expanded, {player.name for player in self.players})
        check_screenshots([item.step for item in expanded])
        return self


def check_players(players: list[PlayerSpec]) -> None:
    """プレイヤー名の重複と予約語を弾く。スイートの players でも同じ規則を使う。"""
    names = [player.name for player in players]
    if len(set(names)) != len(names):
        raise ValueError("player names must be unique")
    if SERVER_TARGET in names:
        raise ValueError(f"{SERVER_TARGET!r} is reserved and cannot be a player name")


def expand_steps(steps: list[Any]) -> list[ExpandedStep]:
    """1 つのステップ列だけを展開する（ブロック番号はこの列の中の通し番号）。"""
    return StepExpander().expand(steps)


def check_step_targets(expanded: list[ExpandedStep], names: set[str]) -> None:
    """展開後のプレイヤー向けのステップが、宣言されたプレイヤーだけを対象にしているかを確かめる。"""
    for item in expanded:
        if isinstance(item.step, PlayerAction) and item.step.on not in names:
            raise ValueError(f"{item.location}: player {item.step.on!r} is not declared in players")


def check_screenshots(steps: list[Any]) -> None:
    """スクリーンショット名の重複と、失敗時の画面に予約した名前の使用を弾く。steps は展開後のステップ。"""
    # スクリーンショットはプレイヤーごとのディレクトリに保存するため、同じプレイヤー内で重複を弾く
    shots = [(step.on, step.name) for step in steps if isinstance(step, Screenshot)]
    duplicates = sorted({f"{on}/{name}" for on, name in shots if shots.count((on, name)) > 1})
    if duplicates:
        raise ValueError(f"duplicate screenshot names: {', '.join(duplicates)}")
    # 失敗時の画面は "failure" という名前で保存するため、シナリオでは使えないようにする
    if any(name == FAILURE_SCREENSHOT for _, name in shots):
        raise ValueError(f"screenshot name {FAILURE_SCREENSHOT!r} is reserved for the failure screenshot")


def parse_scenario(data: Any) -> Scenario:
    """JSON / YAML から読み込んだ値を検証し、Scenario に変換する。"""
    try:
        return Scenario.model_validate(data)
    except ValidationError as error:
        raise ScenarioError(str(error)) from error
