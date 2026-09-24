"""ステップを束ねるブロック（parallel / repeat）。対象（on）を持たない。

中身の steps は Step（model.py）を再帰的に参照するため、ここでは前方参照にしておき、
model.py で Step を定義した後に model_rebuild する。計画（discovery）の時点で
expansion.py が平らなステップ列へ展開するので、ランナーはブロックそのものを扱わない。
"""

from typing import TYPE_CHECKING, Annotated, Literal

from pydantic import ConfigDict, Field

from fukurou.scenario.common import VARIABLE_NAME_PATTERN, ActionModel

if TYPE_CHECKING:
    from fukurou.scenario.model import Step

# repeat の回数の上限。展開後のステップ数の上限（1 テスト 1000）とは別に、明らかな誤りを早く弾く
MAX_REPEAT_TIMES = 100
DEFAULT_REPEAT_VARIABLE = "i"


class Parallel(ActionModel):
    """子ステップを同時に実行し、すべて終わるのを待つ。どれかが失敗するとブロックも失敗する。"""

    action: Literal["parallel"] = "parallel"
    steps: Annotated[list["Step"], Field(min_length=1)]


class Repeat(ActionModel):
    """子ステップの列を times 回繰り返す。計画の時点で展開（アンロール）される。

    子ステップの文字列（text / command / pattern / name / key）の "${<as>}" は 1 始まり、
    "${<as>0}" は 0 始まりの繰り返し番号に置き換わる。
    """

    # as は Python の予約語なので属性名を変える。Python から作るときも as_ で渡せるようにする
    model_config = ConfigDict(populate_by_name=True)

    action: Literal["repeat"] = "repeat"
    times: Annotated[int, Field(ge=1, le=MAX_REPEAT_TIMES)]
    as_: Annotated[str, Field(pattern=VARIABLE_NAME_PATTERN)] = Field(default=DEFAULT_REPEAT_VARIABLE, alias="as")
    steps: Annotated[list["Step"], Field(min_length=1)]

    @property
    def variables(self) -> tuple[str, str]:
        """このブロックが定義する変数名（1 始まり, 0 始まり）。"""
        return self.as_, f"{self.as_}0"
