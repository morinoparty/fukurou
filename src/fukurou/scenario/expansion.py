"""ステップ列のブロック（repeat / parallel / 複数プレイヤーの on）を、平らなステップ列へ展開する。

外部に依存しない純粋な処理。シナリオ・スイートの検証（読み込み時）と、テストの計画（discovery）の
両方がこの展開を使うため、ブロックに関する規則はすべてここに集める:

- repeat は times 回アンロールし、子ステップの文字列の "${<as>}" / "${<as>0}" を繰り返し番号に置き換える
- 複数プレイヤーの on は、プレイヤーごとの複製を子とする parallel と同じに扱う
- parallel の入れ子、17 本以上のレーン、同じプレイヤーへの同時のクライアント入力はエラー
- 1 テストの展開後のステップ数は MAX_PLANNED_STEPS まで

展開後の各ステップは、どの parallel ブロックのどのレーンか・どの repeat の何回目かを持つ。
ランナーは同じ parallel.block を持つ（計画順で連続した）ステップを 1 つのブロックとして、
レーンごとに 1 スレッドで実行する。
"""

from dataclasses import dataclass, field
from typing import Any

from pydantic import ValidationError

from fukurou.scenario.blocks import Parallel, Repeat
from fukurou.scenario.common import PLACEHOLDER
from fukurou.scenario.player_actions import Chat, PlayerAction, PressKey, Screenshot, TypeText

# 1 つの parallel ブロックが同時に走らせる子（レーン）の上限。複数プレイヤーの on は人数分に数える
MAX_LANES = 16
# 1 テスト（beforeEach + fixtures + steps）の展開後のステップ数の上限
MAX_PLANNED_STEPS = 1000
# プレースホルダーを置き換える文字列フィールド（on は置き換えない）
TEMPLATE_FIELDS = ("text", "command", "pattern", "name", "key")
# クライアントの画面・キーボードを使うアクション。同じプレイヤーへ同時に送ると入力が混ざる
CLIENT_INPUT_ACTIONS = (PressKey, TypeText, Chat, Screenshot)


@dataclass(frozen=True)
class ParallelPosition:
    """parallel ブロックの中の位置。block はテスト内の通し番号（展開したインスタンスごと）、lane は子の番号。"""

    block: int
    lane: int


@dataclass(frozen=True)
class RepeatPosition:
    """repeat ブロックの何回目か。block はテスト内の通し番号（展開したインスタンスごと）、iteration は 1 始まり。"""

    block: int
    iteration: int
    of: int


@dataclass(frozen=True)
class ExpandedStep:
    """展開後の 1 ステップ。step はブロックを含まず、on は常に 1 つの対象になっている。"""

    step: Any
    # エラーメッセージ用の元の位置（"step 2 > step 0" のように入れ子をたどる）
    location: str
    parallel: ParallelPosition | None = None
    # 外側の repeat から順に並べる
    repeat: tuple[RepeatPosition, ...] = ()


@dataclass(frozen=True)
class _Scope:
    """展開中の文脈。有効な変数・外側の repeat・今いる parallel のレーン。"""

    variables: dict[str, int] = field(default_factory=dict)
    repeat: tuple[RepeatPosition, ...] = ()
    lane: ParallelPosition | None = None


class StepExpander:
    """1 テスト分のステップを展開する。

    ブロック番号は beforeEach・fixture・テスト自身のステップを通したテスト内の通し番号にするため、
    1 テストの計画では同じインスタンスで各層を順に展開する。
    """

    def __init__(self, max_steps: int = MAX_PLANNED_STEPS):
        self._max_steps = max_steps
        self._count = 0
        self._parallel_blocks = 0
        self._repeat_blocks = 0

    def expand(self, steps: list[Any], prefix: str = "") -> list[ExpandedStep]:
        """ステップ列を展開する。規則に反する場合は ValueError（位置付きのメッセージ）を送出する。"""
        return self._steps(steps, _Scope(), prefix)

    # --- 内部処理 ---------------------------------------------------------------

    def _steps(self, steps: list[Any], scope: _Scope, parent: str) -> list[ExpandedStep]:
        expanded: list[ExpandedStep] = []
        for index, step in enumerate(steps):
            location = f"{parent} > step {index}" if parent else f"step {index}"
            expanded.extend(self._step(step, scope, location))
        return expanded

    def _step(self, step: Any, scope: _Scope, location: str) -> list[ExpandedStep]:
        if isinstance(step, Repeat):
            return self._repeat(step, scope, location)
        if isinstance(step, Parallel):
            return self._parallel(step.steps, scope, location)
        if isinstance(step, PlayerAction) and len(step.targets) > 1:
            # 複数プレイヤーの on は、プレイヤーごとの複製を子とする parallel の略記
            return self._parallel([step], scope, location)
        return [self._leaf(step, scope, location)]

    def _repeat(self, block: Repeat, scope: _Scope, location: str) -> list[ExpandedStep]:
        # 内側の repeat が外側と同じ名前（"i" と "i0" の組も含む）を使うと、どちらの番号か決まらない
        clashes = sorted(set(block.variables) & set(scope.variables))
        if clashes:
            raise ValueError(
                f"{location}: repeat variable {block.as_!r} clashes with an outer repeat ({', '.join(clashes)}); "
                "use a different 'as'"
            )
        number = self._repeat_blocks
        self._repeat_blocks += 1
        expanded: list[ExpandedStep] = []
        for iteration in range(1, block.times + 1):
            inner = _Scope(
                variables=scope.variables | {block.as_: iteration, f"{block.as_}0": iteration - 1},
                repeat=(*scope.repeat, RepeatPosition(block=number, iteration=iteration, of=block.times)),
                lane=scope.lane,
            )
            expanded.extend(self._steps(block.steps, inner, location))
        return expanded

    def _parallel(self, children: list[Any], scope: _Scope, location: str) -> list[ExpandedStep]:
        if scope.lane is not None:
            # レーンの中でさらに並列にすると、スレッドと入力の衝突の規則が入れ子になるため禁止する
            raise ValueError(
                f"{location}: parallel blocks cannot be nested "
                "(a step with several players in on is a parallel block too)"
            )
        # parallel の直接の子に書いた複数プレイヤーの on は、入れ子にせずプレイヤーごとのレーンに広げる。
        # エラーは利用者が書いた子の番号で報告したいので、各レーンがどの子から来たかも持っておく
        lane_steps: list[tuple[int, Any]] = [
            (child_index, lane_step)
            for child_index, child in enumerate(children)
            for lane_step in (
                [child.model_copy(update={"on": name}) for name in child.targets] if isinstance(child, PlayerAction) else [child]
            )
        ]
        if len(lane_steps) > MAX_LANES:
            raise ValueError(
                f"{location}: a parallel block may run at most {MAX_LANES} children at once, got {len(lane_steps)}"
            )
        number = self._parallel_blocks
        self._parallel_blocks += 1
        lanes: list[list[ExpandedStep]] = []
        for lane, (_, child) in enumerate(lane_steps):
            inner = _Scope(variables=scope.variables, repeat=scope.repeat, lane=ParallelPosition(number, lane))
            lanes.append(self._step(child, inner, location))
        _check_client_input(lanes, [child_index for child_index, _ in lane_steps], location)
        # レーン 0 のステップ → レーン 1 のステップ …の順に並べる（計画順の index はこの順）
        return [step for lane in lanes for step in lane]

    def _leaf(self, step: Any, scope: _Scope, location: str) -> ExpandedStep:
        self._count += 1
        if self._count > self._max_steps:
            raise ValueError(f"a test may have at most {self._max_steps} planned steps after expanding repeat blocks")
        return ExpandedStep(
            step=_render(step, scope.variables, location),
            location=location,
            parallel=scope.lane,
            repeat=scope.repeat,
        )


def _render(step: Any, variables: dict[str, int], location: str) -> Any:
    """プレースホルダーを繰り返し番号に置き換え、置き換えた値でステップを検証し直す。"""
    data = step.model_dump()
    changed = False
    for name in TEMPLATE_FIELDS:
        value = data.get(name)
        if isinstance(value, str) and PLACEHOLDER.search(value):
            # repeat の外では変数が 1 つも無いので、どのプレースホルダーも未知としてエラーになる
            data[name] = _substitute(value, variables, f"{location}: {name}")
            changed = True
    if not changed:
        return step
    try:
        # 置き換え後の値に対して、エイリアス（Enter → Return）や正規表現の検証をもう一度かける
        return type(step).model_validate(data)
    except ValidationError as error:
        details = "; ".join(f"{'.'.join(map(str, item['loc']))}: {item['msg']}" for item in error.errors())
        raise ValueError(f"{location}: {details}") from error


def _substitute(text: str, variables: dict[str, int], where: str) -> str:
    """"${name}" を 1 回の走査で置き換える。未知の名前はエラーにする。"""

    def replace(match) -> str:
        name = match.group(1)
        if name not in variables:
            if not variables:
                raise ValueError(f"{where}: unknown placeholder '${{{name}}}' (placeholders only work inside a repeat)")
            known = ", ".join(f"${{{variable}}}" for variable in sorted(variables))
            raise ValueError(f"{where}: unknown placeholder '${{{name}}}' (available: {known})")
        return str(variables[name])

    return PLACEHOLDER.sub(replace, text)


def _check_client_input(lanes: list[list[ExpandedStep]], children_of_lanes: list[int], location: str) -> None:
    """同じプレイヤーへクライアント入力（キー・文字・チャット・撮影）を送るレーンが 2 本以上ないか確かめる。

    children_of_lanes はレーンごとの元の子の番号。複数プレイヤーの on で広げたレーンは同じ子の番号を持つが、
    on の名前は一意なので、衝突は常に別の子どうしになる。メッセージは利用者が書いた子の番号で報告する。
    """
    # プレイヤー名 → そのプレイヤーへ入力する子の番号
    owners: dict[str, int] = {}
    for child, steps in zip(children_of_lanes, lanes):
        players = {step.step.on for step in steps if isinstance(step.step, CLIENT_INPUT_ACTIONS)}
        for player in sorted(players):
            if player in owners:
                raise ValueError(
                    f"{location}: parallel children {owners[player]} and {child} both send client input "
                    f"(press_key, type_text, chat or screenshot) to {player}"
                )
            owners[player] = child
