"""選択（--suite / --scenarios / --scenario-file / --scenario とフィルタ）から、実行するテストの一覧を作る。

ファイルを読むこと以外に副作用を持たない純粋な処理にしておき、サーバーやクライアントを起動する前
（アクションでは apt より前の `fukurou list`）に入力の誤りを検出できるようにしている。
"""

from dataclasses import dataclass, field, replace
from fnmatch import fnmatchcase
import glob
import hashlib
from pathlib import Path
import re
from typing import Literal

from pydantic import ValidationError

from fukurou.errors import InvalidInputError
from fukurou.scenario.loader import ScenarioSource, parse_document
from fukurou.scenario.model import (
    Isolation,
    PlayerSpec,
    Scenario,
    ScenarioError,
    Step,
    check_screenshots,
    check_step_targets,
)
from fukurou.scenario.player_actions import PlayerAction
from fukurou.scenario.suite import Suite
from fukurou.versions import spec_includes

# テスト id はファイル名の stem。tests/<id>/... のパスとビューアのルートにそのまま使う
TEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
# ステップがどの層から来たか（result.json の steps[].phase）
StepPhase = Literal["beforeEach", "fixture", "test"]
DEFAULT_ISOLATION: Isolation = "reset"


class SuiteError(InvalidInputError):
    """スイート・シナリオ・選択が不正な場合に送出する。複数の問題は 1 行ずつメッセージに並べる。"""


@dataclass(frozen=True)
class Selection:
    """どのテストを実行するか。CLI の引数とアクションの入力で共通の形。"""

    suite: Path | None = None
    scenario_files: list[Path] = field(default_factory=list)
    # カレントディレクトリ基準の glob（スイートの scenarios はスイートファイルのディレクトリ基準）
    scenario_globs: list[str] = field(default_factory=list)
    scenario_text: str | None = None
    # テスト id と fnmatch で照合する（いずれかに一致）
    test_filters: list[str] = field(default_factory=list)
    # いずれかのタグを持つテストだけ（test_filters と両方あれば AND）
    tag_filters: list[str] = field(default_factory=list)
    # 指定すると全テストの isolation をこの値で上書きする
    isolation: Isolation | None = None

    @property
    def has_source(self) -> bool:
        """テストの出どころ（スイート・glob・ファイル・インライン）が 1 つ以上あるか。"""
        return bool(self.suite or self.scenario_files or self.scenario_globs or self.scenario_text)


@dataclass(frozen=True)
class PlannedStep:
    """展開済みの 1 ステップ。beforeEach → fixture → テスト自身の順に並ぶ。"""

    phase: StepPhase
    # phase が fixture のときだけ fixture の名前
    fixture: str | None
    step: Step
    # fixture / beforeEach のステップがこのテストに参加しないプレイヤーを対象にしている場合の理由。
    # ランナーはこのステップを実行せず skipped（error にこの文字列）として記録する
    skip_reason: str | None = None


@dataclass(frozen=True)
class TestSpec:
    """実行する 1 テスト。スイートの既定値と fixture を解決した後の形。"""

    # pytest がテストクラスとして収集しないようにする
    __test__ = False

    id: str
    name: str
    # 実行順（reset のテストを宣言順 → fresh-server のテストを宣言順）
    order: int
    # "file:<path>" または "inline"
    source: str
    sha256: str
    tags: list[str]
    isolation: Isolation
    timeout: float
    versions: str | None
    players: list[PlayerSpec]
    steps: list[PlannedStep]

    def skip_reason_for(self, version: str, releases: list[str]) -> str | None:
        """versions: が実行中のバージョンを含まなければ skipped の理由を、含めば None を返す。"""
        if self.versions is None or spec_includes(self.versions, version, releases):
            return None
        return f"versions: {self.versions} does not include {version}"


@dataclass(frozen=True)
class Discovery:
    """発見の途中経過。validate が有効なテストと問題の両方を表示できるよう、例外にせず集める。"""

    suite: Suite | None
    # フィルタを適用し、実行順に並べたテスト
    tests: list[TestSpec]
    # 見つかった問題（1 件 1 行）。1 件でもあれば discover_tests は SuiteError にする
    errors: list[str]


def discover_tests(selection: Selection) -> tuple[Suite | None, list[TestSpec]]:
    """選択からテストの一覧（実行順）を作る。不正な入力や 0 件の選択は SuiteError にする。"""
    discovery = inspect_tests(selection)
    if discovery.errors:
        raise SuiteError("\n".join(discovery.errors))
    if not discovery.tests:
        # workflow_dispatch の入力のタイポなどで、何も実行せずに成功してしまわないようにする
        raise SuiteError(f"no tests were selected{_filter_description(selection)}")
    return discovery.suite, discovery.tests


def inspect_tests(selection: Selection) -> Discovery:
    """discover_tests の本体。スイート自体が読めない場合だけ SuiteError を送出し、他の問題は集める。"""
    if not selection.has_source:
        raise SuiteError("select tests with --suite, --scenarios, --scenario-file or --scenario")
    suite = load_suite(selection.suite) if selection.suite is not None else None
    errors: list[str] = []
    sources = _collect_sources(selection, suite, errors)
    parsed = _parse_sources(sources, errors)
    known_players = _known_players(suite, [scenario for _, scenario in parsed])
    _check_suite_targets(suite, known_players, errors)
    tests: list[TestSpec] = []
    for source, scenario in parsed:
        try:
            tests.append(_expand(source, scenario, suite, selection.isolation))
        except ValueError as error:
            errors.append(f"{source.name}: {error}")
    selected = [test for test in tests if _matches(test, selection)]
    return Discovery(suite=suite, tests=execution_order(selected), errors=errors)


def load_suite(path: Path) -> Suite:
    """スイートファイルを読み込んで検証する。出どころとハッシュも持たせる。"""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise SuiteError(f"could not read the suite file {path}: {error}") from error
    try:
        suite = Suite.model_validate(parse_document(text, kind="suite"))
    except (ScenarioError, ValidationError) as error:
        raise SuiteError(f"invalid suite {path}: {error}") from error
    sha256 = hashlib.sha256(text.encode("utf-8")).hexdigest()
    # 利用者が渡したパス表記のまま記録し、ビューアで見覚えのある形にする
    return suite.with_origin(f"file:{path.as_posix()}", sha256, path.parent)


def execution_order(tests: list[TestSpec]) -> list[TestSpec]:
    """reset のテストを宣言順に並べ、その後に fresh-server のテストを宣言順に並べて order を振り直す。

    fresh-server のテストを後ろにまとめることで、共有セッションを使い切ってから再起動する。
    """
    shared = [test for test in tests if test.isolation != "fresh-server"]
    fresh = [test for test in tests if test.isolation == "fresh-server"]
    return [replace(test, order=order) for order, test in enumerate(shared + fresh)]


# --- 内部処理 ---------------------------------------------------------------------


def _collect_sources(selection: Selection, suite: Suite | None, errors: list[str]) -> list[ScenarioSource]:
    """スイートの glob → --scenarios → --scenario-file → --scenario の順にシナリオを集める。同じファイルは 1 回。"""
    paths: list[Path] = []
    if suite is not None:
        for pattern in suite.scenarios:
            paths.extend(_expand_glob(suite.directory, pattern))
    for pattern in selection.scenario_globs:
        paths.extend(_expand_glob(Path(), pattern))
    paths.extend(selection.scenario_files)

    sources: list[ScenarioSource] = []
    seen: set[Path] = set()
    for path in paths:
        # 複数の glob やファイル指定に一致しても、同じファイルは最初の 1 回だけ使う
        key = path.resolve()
        if key in seen:
            continue
        seen.add(key)
        try:
            sources.append(ScenarioSource.from_file(path))
        except ScenarioError as error:
            errors.append(str(error))
    if selection.scenario_text is not None:
        sources.append(ScenarioSource.inline(selection.scenario_text))
    return sources


def _expand_glob(base: Path, pattern: str) -> list[Path]:
    """glob を base 基準で展開し、ファイルだけをソートして返す。

    一致しない glob はエラーにしない（scenarios/*.json と scenarios/*.yml を並べて片方だけ使う場合など）。
    タイポで全体が 0 件になった場合は discover_tests が入力の誤りにする。
    """
    if Path(pattern).is_absolute():
        matches = [Path(match) for match in glob.glob(pattern, recursive=True)]
    else:
        # 返すパスは base を前に付けた形にし、source に "game-test/scenarios/x.json" のように残す
        matches = [base / match for match in glob.glob(pattern, root_dir=base, recursive=True)]
    return sorted(path for path in matches if path.is_file())


def _parse_sources(sources: list[ScenarioSource], errors: list[str]) -> list[tuple[ScenarioSource, Scenario]]:
    """id の検証と重複の検出、シナリオの検証を行い、有効なものだけを返す。"""
    by_id: dict[str, list[ScenarioSource]] = {}
    for source in sources:
        by_id.setdefault(source.name, []).append(source)
    parsed: list[tuple[ScenarioSource, Scenario]] = []
    for source in sources:
        if len(by_id[source.name]) > 1:
            # 最初の出現の位置で 1 回だけ報告する
            if by_id[source.name][0] is source:
                origins = ", ".join(duplicate.source for duplicate in by_id[source.name])
                errors.append(f"{source.name}: duplicate test id (from {origins})")
            continue
        if not TEST_ID_PATTERN.fullmatch(source.name):
            errors.append(
                f"{source.source}: the test id {source.name!r} (the file name without its extension) "
                f"must match {TEST_ID_PATTERN.pattern}"
            )
            continue
        try:
            parsed.append((source, source.parse()))
        except ScenarioError as error:
            errors.append(f"{source.name}: invalid scenario: {error}")
    return parsed


def _known_players(suite: Suite | None, scenarios: list[Scenario]) -> set[str]:
    """スイートとすべてのテストに宣言されたプレイヤー名の和集合。"""
    names = {player.name for player in suite.players} if suite is not None else set()
    for scenario in scenarios:
        names.update(player.name for player in scenario.players or [])
    return names


def _check_suite_targets(suite: Suite | None, known: set[str], errors: list[str]) -> None:
    """fixture / beforeEach のステップが、どこにも宣言されていないプレイヤー（タイポ）を対象にしていないか確かめる。"""
    if suite is None:
        return
    groups = [("beforeEach", suite.before_each), *((f"fixture {name!r}", steps) for name, steps in suite.fixtures.items())]
    for label, steps in groups:
        for index, step in enumerate(steps):
            if isinstance(step, PlayerAction) and step.on not in known:
                errors.append(f"suite {label} step {index}: player {step.on!r} is not declared in any players")


def _expand(source: ScenarioSource, scenario: Scenario, suite: Suite | None, isolation: Isolation | None) -> TestSpec:
    """スイートの既定値と fixture を解決して TestSpec を作る。order は後で振り直す。"""
    # テストの players は、書いてあればスイートの一覧を完全に置き換える
    players = list(scenario.players) if scenario.players is not None else list(suite.players if suite else [])
    if not players:
        raise ValueError("no players: declare players in the scenario or in the suite")
    names = {player.name for player in players}
    check_step_targets(scenario.steps, names)

    steps: list[PlannedStep] = []
    if suite is not None:
        steps.extend(_plan("beforeEach", None, suite.before_each, names))
    for fixture in scenario.use:
        if suite is None or fixture not in suite.fixtures:
            defined = ", ".join(suite.fixtures) if suite is not None and suite.fixtures else "none"
            raise ValueError(f"unknown fixture {fixture!r} in use (defined: {defined})")
        steps.extend(_plan("fixture", fixture, suite.fixtures[fixture], names))
    steps.extend(PlannedStep(phase="test", fixture=None, step=step) for step in scenario.steps)
    # fixture とテストのスクリーンショット名が衝突しないか、実行されるステップだけで確かめる
    check_screenshots([planned.step for planned in steps if planned.skip_reason is None])

    test_id = source.name
    return TestSpec(
        id=test_id,
        name=scenario.name or test_id,
        order=0,
        source=source.source,
        sha256=source.sha256,
        tags=list(scenario.tags),
        # --isolation > テストの宣言 > スイートの既定 > reset の順で決める
        isolation=isolation or scenario.isolation or (suite.isolation if suite is not None else DEFAULT_ISOLATION),
        timeout=scenario.timeout,
        versions=scenario.versions,
        players=players,
        steps=steps,
    )


def _plan(phase: StepPhase, fixture: str | None, steps: list[Step], names: set[str]) -> list[PlannedStep]:
    """共有のステップ列を展開する。このテストに居ないプレイヤー向けのステップには理由を付けて残す。"""
    planned = []
    for step in steps:
        skip = f"player {step.on} is not in this test" if isinstance(step, PlayerAction) and step.on not in names else None
        planned.append(PlannedStep(phase=phase, fixture=fixture, step=step, skip_reason=skip))
    return planned


def _matches(test: TestSpec, selection: Selection) -> bool:
    """--test（id の fnmatch、OR）と --tag（OR）の両方を満たすか。指定の無いフィルタは常に満たす。"""
    by_id = not selection.test_filters or any(fnmatchcase(test.id, pattern) for pattern in selection.test_filters)
    by_tag = not selection.tag_filters or bool(set(test.tags) & set(selection.tag_filters))
    return by_id and by_tag


def _filter_description(selection: Selection) -> str:
    parts = []
    if selection.test_filters:
        parts.append(f"--test {', '.join(selection.test_filters)}")
    if selection.tag_filters:
        parts.append(f"--tag {', '.join(selection.tag_filters)}")
    return f" (filters: {'; '.join(parts)})" if parts else ""

