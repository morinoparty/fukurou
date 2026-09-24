"""スイートファイル（全テストで共有するプレイヤー・fixture・リセットの設定）のモデル。"""

from dataclasses import dataclass, field
from pathlib import Path
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    NonNegativeFloat,
    PositiveInt,
    PrivateAttr,
    field_validator,
    model_validator,
)

from fukurou.scenario.common import NonEmptyStr, PlayerName
from fukurou.scenario.model import Isolation, PlayerSpec, SafeName, Step, check_players, check_screenshots, expand_steps

# fill コマンドが 1 回で変更できるブロック数の既定の上限（gamerule commandModificationBlockLimit）。
# gamerule は変えずに済むよう、アリーナをこの範囲に収める
MAX_ARENA_BLOCKS = 32768
DEFAULT_SETTLE = 2.0
GameMode = Literal["survival", "creative", "adventure", "spectator"]


class ArenaSpec(BaseModel):
    """リセット時に空気で埋める領域。x, z は原点を中心に size、y は地表から height ブロック。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    size: PositiveInt = 32
    height: PositiveInt = 24

    @model_validator(mode="after")
    def _check_block_limit(self) -> "ArenaSpec":
        # 1 回の fill で埋められないと、リセットが "Too many blocks" で失敗する
        blocks = self.size * self.size * self.height
        if blocks > MAX_ARENA_BLOCKS:
            raise ValueError(
                f"the arena has {blocks} blocks (size*size*height); it must be at most {MAX_ARENA_BLOCKS}"
            )
        return self


class Suite(BaseModel):
    """スイートファイル全体。glob と相対パスはスイートファイルのディレクトリを基準にする。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    # エディターの補完用に JSON Schema の URL を書けるようにする（実行には使わない）
    schema_url: str | None = Field(default=None, alias="$schema")
    # 順序付きの glob。各 glob はソートして展開し、同じファイルは最初の 1 回だけ使う
    scenarios: list[NonEmptyStr] = Field(default_factory=list)
    # players を省略したテストの既定。全テストの和集合がサーバーに参加する
    players: list[PlayerSpec] = Field(default_factory=list)
    isolation: Isolation = "reset"
    # false ならアリーナのリセット（fill）をしない（ワールドを持ち込む場合など）
    arena: ArenaSpec | Literal[False] = Field(default_factory=ArenaSpec)
    gamemode: GameMode = "survival"
    # リセット時の tp 先（"x y z" または "x y z yaw pitch"）。省略したプレイヤーは既定のスロット
    spawn: dict[PlayerName, NonEmptyStr] = Field(default_factory=dict)
    # リセット後、クライアントがブロック更新を受け取るまで待つ秒数
    settle: NonNegativeFloat = DEFAULT_SETTLE
    # 名前付きのステップ列。テストの use: で参照する
    fixtures: dict[SafeName, Annotated[list[Step], Field(min_length=1)]] = Field(default_factory=dict)
    # 全テストの前（fixtures より前）に実行するステップ
    before_each: list[Step] = Field(default_factory=list, alias="beforeEach")

    # スイートファイルの出どころ。result.json の suite 欄と、glob の基準ディレクトリに使う
    _source: str | None = PrivateAttr(default=None)
    _sha256: str | None = PrivateAttr(default=None)
    _directory: Path = PrivateAttr(default_factory=Path)

    @field_validator("spawn")
    @classmethod
    def _check_spawn(cls, spawn: dict[str, str]) -> dict[str, str]:
        # tp と spawnpoint にそのまま渡すため、数値 3 つ（座標）か 5 つ（座標 + 向き）に限る
        for name, position in spawn.items():
            parts = position.split()
            if len(parts) not in (3, 5) or not all(_is_number(part) for part in parts):
                raise ValueError(f"spawn of {name}: expected 'x y z' or 'x y z yaw pitch', got {position!r}")
        return spawn

    @model_validator(mode="after")
    def _check_references(self) -> "Suite":
        check_players(self.players)
        # fixture ごとに、ブロックの規則とスクリーンショット名の重複・予約名を展開して検証する
        # （テストとの重複と、テスト全体のステップ数の上限は discovery の展開時に見る）
        groups = [*((f"fixture {name!r}", steps) for name, steps in self.fixtures.items()), ("beforeEach", self.before_each)]
        for label, steps in groups:
            try:
                check_screenshots([item.step for item in expand_steps(steps)])
            except ValueError as error:
                raise ValueError(f"{label}: {error}") from error
        return self

    def with_origin(self, source: str, sha256: str, directory: Path) -> "Suite":
        """読み込んだファイルの情報を持たせる（モデルのフィールドではないので JSON Schema には出ない）。"""
        self._source = source
        self._sha256 = sha256
        self._directory = directory
        return self

    @property
    def source(self) -> str | None:
        """"file:<path>"。ファイルから読まなかった場合は None。"""
        return self._source

    @property
    def sha256(self) -> str | None:
        return self._sha256

    @property
    def directory(self) -> Path:
        """scenarios の glob の基準ディレクトリ。"""
        return self._directory

    @property
    def reset_spec(self) -> "ResetSpec":
        return ResetSpec(arena=self.arena, gamemode=self.gamemode, spawn=dict(self.spawn), settle=self.settle)


@dataclass(frozen=True)
class ResetSpec:
    """テストの前のリセットに使う設定。スイートが無い場合は既定値で同じリセットを行う。"""

    arena: ArenaSpec | Literal[False] = field(default_factory=ArenaSpec)
    gamemode: GameMode = "survival"
    spawn: dict[str, str] = field(default_factory=dict)
    settle: float = DEFAULT_SETTLE

    @classmethod
    def of(cls, suite: Suite | None) -> "ResetSpec":
        """スイートがあればその設定、無ければ既定値を返す。"""
        return suite.reset_spec if suite is not None else cls()


def _is_number(text: str) -> bool:
    try:
        float(text)
    except ValueError:
        return False
    return True
